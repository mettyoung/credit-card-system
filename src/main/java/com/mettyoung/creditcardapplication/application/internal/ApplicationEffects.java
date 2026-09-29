package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.audit.AuditEntry;
import com.mettyoung.creditcardapplication.audit.AuditEventType;
import com.mettyoung.creditcardapplication.audit.Audits;
import com.mettyoung.creditcardapplication.shared.outbox.DomainEvent;
import com.mettyoung.creditcardapplication.shared.outbox.OutboxWriter;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The consequences that commit <em>with</em> the fact: the audit row and the outbox row, written in the same
 * transaction that made it true. What happens <em>next</em> is {@link ApplicationProcess}, in a later
 * transaction the relay opens — possibly on another instance, possibly after a restart.
 * <p>
 * That split is the whole point of the outbox, which is why the two are separate classes rather than two
 * methods on one: they run in different transactions, fail in different ways — a throw here rolls the
 * applicant's request back, a throw there leaves the row unpublished for the next poll — and change for
 * different reasons.
 * <p>
 * Reached through {@code @DomainEvents}: the aggregate records what it did, Spring Data publishes that when
 * the repository saves, and this listens. No service has to remember to write both rows.
 * <p>
 * A plain {@link EventListener}, not {@code @TransactionalEventListener}: publication happens inside
 * {@code save}, so this runs on the same thread inside the caller's transaction.
 * {@code AFTER_COMMIT} would be wrong twice over — {@code Audits.record} is {@code MANDATORY} and would
 * refuse for want of a transaction, and an outbox row written after the commit it describes could be lost by
 * a crash in between.
 * <p>
 * One method per event as more arrive; the name is deliberately not tied to submission.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class ApplicationEffects {

    private final ApplicationRepository applications;
    private final Audits auditLog;
    private final OutboxWriter outbox;

    @EventListener
    void on(DomainEvent.ApplicationSubmitted event) {
        // The event carries ids only, because it is also the durable outbox payload. The product code comes
        // from the aggregate, which is already in the persistence context — this costs no query.
        Application application = applications.findById(event.applicationId()).orElseThrow();

        auditLog.record(AuditEntry.byApplicant(event.applicationId(), AuditEventType.WORKFLOW_STARTED,
                application.getUserId(), Map.of("cardProductCode", application.getCardProductCode())));
        outbox.write(event);
    }
}
