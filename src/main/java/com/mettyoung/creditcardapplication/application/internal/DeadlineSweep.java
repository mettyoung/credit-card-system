package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.ApplicationStatus;
import com.mettyoung.creditcardapplication.shared.outbox.DomainEvent;
import com.mettyoung.creditcardapplication.shared.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FR9: the two deadlines, as a column ({@code status_changed_at}) and this poller - never an in-memory timer, so
 * a restart or a missed run loses nothing.
 * <p>
 * It never transitions an application. An overdue NEEDS_INFO is reported through the outbox, one transaction
 * each, and {@link ApplicationProcess} decides; a crash between finding and expiring is harmless, because the
 * next run finds it again and the orchestrator's guard applies the expiry once. An overdue referral is only
 * reported: only a person decides one.
 */
@Component
class DeadlineSweep {

    private static final Logger log = LoggerFactory.getLogger(DeadlineSweep.class);

    private final ApplicationRepository applications;
    private final OutboxWriter outbox;
    private final DeadlineProperties deadlines;
    private final TransactionTemplate transaction;
    private final Clock clock;

    DeadlineSweep(ApplicationRepository applications, OutboxWriter outbox, DeadlineProperties deadlines,
                  PlatformTransactionManager transactionManager, Clock clock) {
        this.applications = applications;
        this.outbox = outbox;
        this.deadlines = deadlines;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** @return how many applications were reported past their NEEDS_INFO deadline */
    @Scheduled(fixedDelayString = "${app.workers.deadline-interval:15m}")
    public int sweep() {
        Instant now = clock.instant();

        List<UUID> expired = applications
                .findByStatusAndStatusChangedAtBefore(ApplicationStatus.NEEDS_INFO, now.minus(deadlines.needsInfo()))
                .stream().map(Application::getId).toList();
        for (UUID id : expired) {
            transaction.executeWithoutResult(status -> outbox.write(new DomainEvent.NeedsInfoExpired(id)));
        }

        long overdueReferrals = applications
                .countByStatusAndStatusChangedAtBefore(ApplicationStatus.REFERRED, now.minus(deadlines.referred()));
        if (overdueReferrals > 0) {
            // A count, never ids or names: this line is for an alert, and the review queue says which ones.
            log.warn("{} referred applications are overdue for review", overdueReferrals);
        }
        return expired.size();
    }
}
