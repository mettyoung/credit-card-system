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
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Recovers callbacks that never arrived, and ends the ones that never will.
 * <p>
 * This is what makes a lost webhook a delay instead of an application stuck forever. It races the inbox worker
 * on the same checks by design: both complete with a conditional update on {@code AWAITING_CALLBACK}, so
 * exactly one wins and the loser updates nothing.
 */
@Component
class Reconciler {

    static final int BATCH = 20;

    private final VendorCheckRepository checks;
    private final IdvPort idv;
    private final Audits auditLog;
    private final OutboxWriter outbox;
    private final OnfidoProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    Reconciler(VendorCheckRepository checks, IdvPort idv, Audits auditLog, OutboxWriter outbox,
               OnfidoProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.checks = checks;
        this.idv = idv;
        this.auditLog = auditLog;
        this.outbox = outbox;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.workers.reconciler-interval:30s}")
    public int reconcileDue() {
        List<Due> due = claim();
        for (Due item : due) {
            if (item.pastDeadline()) {
                failPastDeadline(item);
                continue;
            }
            VendorResult<IdvOutcome> result = idv.status(new VendorRef(item.vendorRef()));
            apply(item, result);
        }
        return due.size();
    }

    private List<Due> claim() {
        // Pushing next_attempt_at forward in the claiming transaction is what stops two passes picking up the
        // same check while the first is still calling the vendor.
        List<Due> due = transaction.execute(status -> {
            Instant now = Instant.now(clock);
            List<VendorCheck> stale = checks.claimStaleCallbacks(now, Limit.of(BATCH));
            return stale.stream()
                    .map(check -> {
                        boolean past = check.isPastDeadline(now);
                        if (!past) {
                            check.scheduleNextPoll(now.plus(properties.pollAfter()));
                            checks.save(check);
                        }
                        return new Due(check.getId(), check.getVendorRef().value(), past);
                    })
                    .toList();
        });
        return due == null ? List.of() : due;
    }

    private void apply(Due item, VendorResult<IdvOutcome> result) {
        transaction.executeWithoutResult(status -> {
            VendorCheck check = checks.findById(item.checkId()).orElseThrow();
            if (!check.isAwaitingCallback()) {
                // The webhook path got there first.
                return;
            }
            if (result instanceof VendorResult.Completed<IdvOutcome> completed) {
                check.complete(completed.value(), completed.rawResponse(), clock.instant());
                checks.save(check);
                auditLog.record(AuditEntry.bySystem(check.getApplicationId(), AuditEventType.VENDOR_CHECK_COMPLETED,
                        Map.of("vendorCheckId", check.getId(), "outcome", completed.value(),
                                "recoveredBy", "reconciler")));
                outbox.write(new DomainEvent.VendorCheckCompleted(check.getApplicationId(), check.getId()));
            }
        });
    }

    private void failPastDeadline(Due item) {
        transaction.executeWithoutResult(status -> {
            VendorCheck check = checks.findById(item.checkId()).orElseThrow();
            if (!check.isAwaitingCallback()) {
                return;
            }
            check.fail("deadline-exceeded", check.getRawResponse(), clock.instant());
            checks.save(check);
            auditLog.record(AuditEntry.bySystem(check.getApplicationId(), AuditEventType.VENDOR_CHECK_FAILED,
                    Map.of("vendorCheckId", check.getId(), "failureCode", "deadline-exceeded")));
            outbox.write(new DomainEvent.VendorCheckCompleted(check.getApplicationId(), check.getId()));
        });
    }

    private record Due(java.util.UUID checkId, String vendorRef, boolean pastDeadline) {
    }
}
