package com.mettyoung.creditcardapplication.vendor.internal;

import com.mettyoung.creditcardapplication.audit.AuditEventType;
import com.mettyoung.creditcardapplication.audit.AuditEntry;
import com.mettyoung.creditcardapplication.audit.Audits;
import com.mettyoung.creditcardapplication.shared.outbox.DomainEvent;
import com.mettyoung.creditcardapplication.shared.outbox.OutboxWriter;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Fetches the result a webhook only announced, and completes the check.
 * <p>
 * The webhook never carries the answer, so a forged or replayed callback can at worst make this re-read a check
 * we already own. The completion is a conditional update on status, so if the reconciler polled the same
 * result first, one of the two writes 0 rows and does nothing.
 */
@Component
class InboxWorker {

    static final int BATCH = 20;

    private final VendorInboxRepository inbox;
    private final VendorCheckRepository checks;
    private final IdvPort idv;
    private final Audits auditLog;
    private final OutboxWriter outbox;
    private final TransactionTemplate transaction;
    private final Clock clock;

    InboxWorker(VendorInboxRepository inbox, VendorCheckRepository checks, IdvPort idv, Audits auditLog,
                OutboxWriter outbox, PlatformTransactionManager transactionManager, Clock clock) {
        this.inbox = inbox;
        this.checks = checks;
        this.idv = idv;
        this.auditLog = auditLog;
        this.outbox = outbox;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.workers.inbox-interval:1s}")
    public int processDue() {
        List<Claim> claims = claim();
        for (Claim claim : claims) {
            // Outside a transaction: this is a call to the vendor.
            VendorResult<IdvOutcome> result = idv.status(new VendorRef(claim.vendorRef()));
            complete(claim, result);
        }
        return claims.size();
    }

    private List<Claim> claim() {
        List<Claim> claims = transaction.execute(status -> {
            List<VendorInboxEvent> due = inbox.claimUnprocessed(Limit.of(BATCH));
            due.forEach(event -> event.markProcessed(clock.instant()));
            inbox.saveAll(due);
            return due.stream().map(event -> new Claim(event.getId(), event.getVendorRef())).toList();
        });
        return claims == null ? List.of() : claims;
    }

    private void complete(Claim claim, VendorResult<IdvOutcome> result) {
        transaction.executeWithoutResult(status -> {
            VendorCheck check = checks.findByVendorRef(new VendorRef(claim.vendorRef())).orElse(null);
            // Only a check still waiting may be completed. This is the conditional update that lets the
            // reconciler and the webhook race safely.
            if (check == null || !check.isAwaitingCallback()) {
                return;
            }
            auditLog.record(AuditEntry.bySystem(check.getApplicationId(), AuditEventType.WEBHOOK_RECEIVED,
                    Map.of("vendorCheckId", check.getId(), "vendorRef", claim.vendorRef())));

            switch (result) {
                case VendorResult.Completed<IdvOutcome> completed -> {
                    check.complete(completed.value(), completed.rawResponse(), clock.instant());
                    checks.save(check);
                    auditLog.record(AuditEntry.bySystem(check.getApplicationId(), AuditEventType.VENDOR_CHECK_COMPLETED,
                            Map.of("vendorCheckId", check.getId(), "outcome", completed.value())));
                    outbox.write(new DomainEvent.VendorCheckCompleted(check.getApplicationId(), check.getId()));
                }
                case VendorResult.Pending<IdvOutcome> ignored -> {
                    // Announced but not ready. Leave it AWAITING_CALLBACK; the reconciler will poll again
                    // until the deadline.
                }
                case VendorResult.Failed<IdvOutcome> ignored -> {
                    // Leave it for the reconciler rather than failing on one bad fetch: the deadline is the
                    // only thing that should end a callback.
                }
            }
        });
    }

    private record Claim(java.util.UUID inboxId, String vendorRef) {
    }
}
