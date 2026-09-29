package com.mettyoung.creditcardapplication.application.internal;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import com.mettyoung.creditcardapplication.application.RequirementType;
import com.mettyoung.creditcardapplication.application.ApplicationStatus;
import com.mettyoung.creditcardapplication.audit.AuditEventType;
import com.mettyoung.creditcardapplication.audit.AuditEntry;
import com.mettyoung.creditcardapplication.audit.Audits;
import com.mettyoung.creditcardapplication.shared.outbox.DomainEvent;
import com.mettyoung.creditcardapplication.shared.outbox.DomainEventListener;
import com.mettyoung.creditcardapplication.shared.outbox.OutboxWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only component that decides what happens next. Workers do one job and report back through the outbox;
 * they never transition an application.
 * <p>
 * One transaction per event, opened by the relay — so this runs after the fact it reacts to has committed,
 * and is the counterpart to {@link ApplicationEffects}, which writes the rows that commit with it.
 * <p>
 * The decision itself is {@link Evaluator}, which is pure — this class only loads, applies and records.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class ApplicationProcess implements DomainEventListener {

    private final ApplicationRepository applications;
    private final EvidenceRequirementRepository requirements;
    private final Audits auditLog;
    private final OutboxWriter outbox;


    /**
     * Applies one event. Runs in the relay's transaction, so the state change, the audit row and any new
     * outbox row commit together.
     * <p>
     * An optimistic-lock conflict — two events landing at once — propagates, and the relay retries the whole
     * thing from the start rather than resolving a half-applied decision.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(DomainEvent event) {
        Application application = applications.findById(event.applicationId()).orElse(null);
        if (application == null) {
            return;
        }

        // Apply what the event itself says, before asking what to do next.
        switch (event) {
            case DomainEvent.ApplicationSubmitted ignored -> {
                // Nothing to apply: submit() already recorded intake.
            }
            case DomainEvent.DocumentUploaded ignored -> {
                // Nothing to apply. The requirement is left exactly as it is so the evaluator can see both
                // that it needs evidence and that a new document has arrived - moving it to PENDING here would
                // hide both facts, and in FR5 the replacement check would never be queued.
            }
            case DomainEvent.ChecksCompleted ignored -> {
                // The seam for the decisioning increment. Nothing subscribes yet.
                return;
            }
        }

        applyNextStep(application);
    }

    private void applyNextStep(Application application) {
        List<EvidenceRequirement> current = requirements.findByApplicationId(application.getId());

        NextStep step = Evaluator.evaluate(application, current);
        switch (step) {
            case NextStep.RequestInfo requestInfo -> requestInfo(application, requestInfo.missing());
            case NextStep.Complete ignored -> complete(application);
            case NextStep.Wait ignored -> {
                // Something else is outstanding. Deliberately nothing.
            }
        }
    }

    private void requestInfo(Application application, List<RequirementType> missing) {
        if (application.getStatus() != ApplicationStatus.VERIFYING) {
            return;
        }
        application.requestInfo();
        applications.save(application);
        auditLog.record(AuditEntry.bySystem(application.getId(), AuditEventType.STATUS_CHANGED,
                Map.of("status", application.getStatus(),
                        "missing", missing.stream().map(Enum::name).toList())));
    }

    private void complete(Application application) {
        if (application.getStatus() != ApplicationStatus.VERIFYING) {
            return;
        }
        application.completeChecks();
        applications.save(application);
        auditLog.record(AuditEntry.bySystem(application.getId(), AuditEventType.STATUS_CHANGED,
                Map.of("status", application.getStatus())));
        outbox.write(new DomainEvent.ChecksCompleted(application.getId()));
    }

    /** Only for the response: the requirements a client may see. */
    @Transactional(readOnly = true)
    public List<EvidenceRequirement> requirementsOf(UUID applicationId) {
        return requirements.findByApplicationId(applicationId);
    }
}
