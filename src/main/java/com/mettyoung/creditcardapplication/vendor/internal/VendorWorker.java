package com.mettyoung.creditcardapplication.vendor.internal;

import com.mettyoung.creditcardapplication.audit.AuditEventType;
import com.mettyoung.creditcardapplication.audit.AuditEntry;
import com.mettyoung.creditcardapplication.audit.Audits;
import com.mettyoung.creditcardapplication.shared.outbox.DomainEvent;
import com.mettyoung.creditcardapplication.shared.outbox.OutboxWriter;
import com.mettyoung.creditcardapplication.vendor.ApplicantSubjects;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runs due vendor checks. Three transactions per check, never one: claim, call, record.
 * <p>
 * The call happens <strong>outside</strong> any transaction. Holding a database transaction open across a
 * network call to a third party is how a slow vendor becomes a connection-pool outage.
 */
@Component
class VendorWorker {

    static final Duration LEASE = Duration.ofMinutes(1);
    static final int BATCH = 10;

    private final VendorCheckRepository checks;
    private final ApplicantSubjects subjects;
    private final IdvPort idv;
    private final Audits auditLog;
    private final OutboxWriter outbox;
    private final OnfidoProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    VendorWorker(VendorCheckRepository checks, ApplicantSubjects subjects, IdvPort idv, Audits auditLog,
                 OutboxWriter outbox, OnfidoProperties properties, PlatformTransactionManager transactionManager,
                 Clock clock) {
        this.checks = checks;
        this.subjects = subjects;
        this.idv = idv;
        this.auditLog = auditLog;
        this.outbox = outbox;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.workers.vendor-interval:1s}")
    public int runDue() {
        List<UUID> claimed = claim();
        for (UUID id : claimed) {
            runOne(id);
        }
        return claimed.size();
    }

    /**
     * Claims and commits before calling anyone. The lease is what makes a crash recoverable: the row looks
     * exactly like one whose worker died, and the claim query picks it up again.
     */
    private List<UUID> claim() {
        List<UUID> ids = transaction.execute(status -> {
            Instant now = Instant.now(clock);
            List<VendorCheck> due = checks.claimDue(now, Limit.of(BATCH));
            due.forEach(check -> check.claim(now, LEASE));
            checks.saveAll(due);
            return due.stream().map(VendorCheck::getId).toList();
        });
        return ids == null ? List.of() : ids;
    }

    private void runOne(UUID checkId) {
        Context context = transaction.execute(status -> {
            VendorCheck check = checks.findById(checkId).orElseThrow();
            return subjects.forApplication(check.getApplicationId())
                    .map(subject -> new Context(check.getId(), check.getDocumentId(),
                            check.getIdempotencyKey(), subject))
                    .orElse(null);
        });
        if (context == null) {
            return;
        }

        VendorResult<IdvOutcome> result =
                idv.submit(context.subject(), context.documentId(), context.idempotencyKey());

        record(context.checkId(), result);
    }

    /** A new transaction, because the previous one was closed before the vendor was called. */
    private void record(UUID checkId, VendorResult<IdvOutcome> result) {
        transaction.executeWithoutResult(status -> {
            VendorCheck check = checks.findById(checkId).orElseThrow();
            Instant now = Instant.now(clock);

            switch (result) {
                case VendorResult.Completed<IdvOutcome> completed -> {
                    check.complete(completed.value(), completed.rawResponse(), now);
                    auditLog.record(AuditEntry.bySystem(check.getApplicationId(), AuditEventType.VENDOR_CHECK_COMPLETED,
                            Map.of("vendorCheckId", check.getId(), "outcome", completed.value())));
                    outbox.write(new DomainEvent.VendorCheckCompleted(check.getApplicationId(), check.getId()));
                }
                case VendorResult.Pending<IdvOutcome> pending -> {
                    // The vendor owns the work now. The deadline and the poller take over from here.
                    check.awaitCallback(pending.ref(), pending.rawResponse(), now,
                            properties.resultDeadline(), properties.pollAfter());
                    auditLog.record(AuditEntry.bySystem(check.getApplicationId(), AuditEventType.VENDOR_CHECK_QUEUED,
                            Map.of("vendorCheckId", check.getId(), "vendorRef", pending.ref().value())));
                }
                case VendorResult.Failed<IdvOutcome> failed -> recordFailure(check, failed, now);
            }
            checks.save(check);
        });
    }

    private void recordFailure(VendorCheck check, VendorResult.Failed<IdvOutcome> failed, Instant now) {
        String code = failed.failure().code();
        if (failed.failure().retryable() && check.hasAttemptsLeft(properties.maxAttempts())) {
            check.scheduleRetry(code, failed.rawResponse(), now, backoff(check.getAttempts()));
            return;
        }
        // Out of attempts, or a failure retrying cannot fix. The requirement becomes UNAVAILABLE and the
        // application still reaches CHECKS_COMPLETE - visible, never a decline.
        check.fail(code, failed.rawResponse(), now);
        auditLog.record(AuditEntry.bySystem(check.getApplicationId(), AuditEventType.VENDOR_CHECK_FAILED,
                Map.of("vendorCheckId", check.getId(), "failureCode", code, "attempts", check.getAttempts())));
        outbox.write(new DomainEvent.VendorCheckCompleted(check.getApplicationId(), check.getId()));
    }

    /** {@code 2^attempts} seconds with jitter, so retries from many instances do not synchronise. */
    private static Duration backoff(int attempts) {
        long seconds = 1L << Math.min(attempts, 8);
        long jitter = ThreadLocalRandom.current().nextLong(seconds + 1);
        return Duration.ofSeconds(seconds).plusSeconds(jitter / 2);
    }

    private record Context(UUID checkId, UUID documentId,
                           com.mettyoung.creditcardapplication.vendor.internal.IdempotencyKey idempotencyKey,
                           ApplicantSubjects.Subject subject) {
    }
}
