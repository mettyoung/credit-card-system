package com.mettyoung.creditcardapplication.application.internal;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import com.mettyoung.creditcardapplication.application.ApplicationStatus;
import com.mettyoung.creditcardapplication.application.RequirementType;
import com.mettyoung.creditcardapplication.audit.Actor;
import com.mettyoung.creditcardapplication.audit.AuditEventType;
import com.mettyoung.creditcardapplication.audit.AuditEntry;
import com.mettyoung.creditcardapplication.audit.Audits;
import com.mettyoung.creditcardapplication.document.Documents;
import com.mettyoung.creditcardapplication.shared.outbox.DomainEvent;
import com.mettyoung.creditcardapplication.shared.outbox.DomainEventListener;
import com.mettyoung.creditcardapplication.shared.outbox.OutboxWriter;
import com.mettyoung.creditcardapplication.vendor.IdentityChecks;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
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
    private final IdentityChecks identityChecks;
    private final Documents documents;
    private final Audits auditLog;
    private final OutboxWriter outbox;
    private final DeadlineProperties deadlines;
    private final Clock clock;

    /**
     * Applies one event. Runs in the relay's transaction, so the state change, the audit row and any new
     * outbox row commit together.
     * <p>
     * An optimistic-lock conflict — two vendor results landing at once — propagates, and the relay retries the
     * whole thing from the start rather than resolving a half-applied decision.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void on(DomainEvent event) {
        Application application = applications.findById(event.applicationId()).orElse(null);
        // FR9.3: decided or expired is final. A late upload or vendor result - a check still in flight when the
        // application expired - is recorded where it lands and moves nothing here.
        if (application == null || application.isTerminal()) {
            return;
        }

        // Apply what the event itself says, before asking what to do next.
        switch (event) {
            case DomainEvent.ApplicationSubmitted ignored -> {
                // Nothing to apply: submit() already recorded intake.
            }
            case DomainEvent.DocumentUploaded ignored -> {
                // Nothing to apply. The requirement is left exactly as it is so the evaluator can see that it
                // needs evidence and that a new document has arrived - moving it to PENDING here would hide
                // both facts and the new check would never be queued.
            }
            case DomainEvent.VendorCheckCompleted completed -> applyCheckResult(application, completed);
            case DomainEvent.ChecksCompleted ignored -> {
                decide(application);
                return;
            }
            case DomainEvent.NeedsInfoExpired ignored -> {
                expire(application);
                return;
            }
        }

        applyNextStep(application);
    }

    private void applyNextStep(Application application) {
        List<EvidenceRequirement> current = requirements.findByApplicationId(application.getId());
        List<Documents.Accepted> applicationDocuments = documents.ofApplication(application.getId());

        NextStep step = Evaluator.evaluate(application, current, applicationDocuments);
        switch (step) {
            case NextStep.StartIdentityCheck start -> startIdentityCheck(application, start.documentId());
            case NextStep.RequestInfo requestInfo -> requestInfo(application, requestInfo.missing());
            case NextStep.Complete ignored -> complete(application);
            case NextStep.Wait ignored -> {
                // Something else is outstanding. Deliberately nothing.
            }
        }
    }

    /**
     * Creates the requirement if it is new, then queues the check. Both in this transaction, so a command can
     * never exist without the requirement that justified it.
     */
    private void startIdentityCheck(Application application, UUID documentId) {
        EvidenceRequirement requirement = requirements
                .findByApplicationIdAndType(application.getId(), RequirementType.IDENTITY)
                .orElseGet(() -> requirements.save(
                        EvidenceRequirement.pending(application.getId(), RequirementType.IDENTITY)));
        requirement.awaitCheckFor(documentId);
        requirements.save(requirement);

        // Commanded through the vendor module's API, in this transaction: the command commits with the
        // decision that asked for it, or not at all.
        UUID checkId = identityChecks.queue(application.getId(), documentId);

        if (application.getStatus() == ApplicationStatus.SUBMITTED) {
            application.startVerifying(clock.instant());
        } else if (application.getStatus() == ApplicationStatus.NEEDS_INFO) {
            application.resumeVerifying(clock.instant());
        }
        applications.save(application);

        auditLog.record(AuditEntry.bySystem(application.getId(), AuditEventType.VENDOR_CHECK_QUEUED,
                Map.of("vendorCheckId", checkId, "documentId", documentId)));
        auditLog.record(AuditEntry.bySystem(application.getId(), AuditEventType.STATUS_CHANGED,
                Map.of("status", application.getStatus())));
    }

    private void requestInfo(Application application, List<RequirementType> missing) {
        if (application.getStatus() != ApplicationStatus.VERIFYING) {
            return;
        }
        application.requestInfo(clock.instant());
        applications.save(application);
        auditLog.record(AuditEntry.bySystem(application.getId(), AuditEventType.STATUS_CHANGED,
                Map.of("status", application.getStatus(), "missing", missing.stream().map(Enum::name).toList())));
    }

    private void complete(Application application) {
        if (application.getStatus() != ApplicationStatus.VERIFYING) {
            return;
        }
        application.completeChecks(clock.instant());
        applications.save(application);
        auditLog.record(AuditEntry.bySystem(application.getId(), AuditEventType.STATUS_CHANGED,
                Map.of("status", application.getStatus())));
        outbox.write(new DomainEvent.ChecksCompleted(application.getId()));
    }

    /**
     * FR8.1: approve a clean result, refer anything else. Only from {@code CHECKS_COMPLETE}, which is what makes
     * a redelivered event decide nothing twice. The evidence pairs each requirement with the outcome of the check
     * that answered it, read through the vendor module's API, so nothing new crosses the module boundary.
     */
    private void decide(Application application) {
        if (application.getStatus() != ApplicationStatus.CHECKS_COMPLETE) {
            return;
        }
        List<DecisionRules.Evidence> evidence = requirements.findByApplicationId(application.getId()).stream()
                .map(requirement -> new DecisionRules.Evidence(requirement.getType(), requirement.getStatus(),
                        requirement.getSourceVendorCheckId() == null ? null
                                : identityChecks.resultOf(requirement.getSourceVendorCheckId())
                                        .map(IdentityChecks.CheckResult::outcome)
                                        .orElse(null)))
                .toList();

        Map<String, Object> payload = new HashMap<>();
        payload.put("by", Actor.SYSTEM);
        switch (DecisionRules.decide(evidence)) {
            case DecisionRules.Decision.Approve ignored -> application.approve(clock.instant());
            case DecisionRules.Decision.Refer refer -> {
                application.refer(refer.reason(), clock.instant());
                payload.put("reason", refer.reason());
            }
        }
        payload.put("outcome", application.getStatus());
        applications.save(application);
        auditLog.record(AuditEntry.bySystem(application.getId(), AuditEventType.DECISION_MADE, payload));
    }

    /**
     * FR9.1: the sweep saw the application past its deadline, but that was then. Re-checked now: an applicant who
     * uploaded in between has moved it back to VERIFYING, and the expiry is a no-op - the upload wins.
     */
    private void expire(Application application) {
        Instant now = clock.instant();
        if (!application.isPastNeedsInfoDeadline(now, deadlines.needsInfo())) {
            return;
        }
        application.expire(now);
        applications.save(application);
        auditLog.record(AuditEntry.bySystem(application.getId(), AuditEventType.STATUS_CHANGED,
                Map.of("status", application.getStatus(), "reason", "EVIDENCE_NOT_PROVIDED")));
    }

    /**
     * Turns a finished check into a requirement state. A FRAUD verdict satisfies the requirement — it is an
     * answer; only an absent answer is UNAVAILABLE.
     */
    private void applyCheckResult(Application application, DomainEvent.VendorCheckCompleted event) {
        IdentityChecks.CheckResult result = identityChecks.resultOf(event.vendorCheckId()).orElse(null);
        if (result == null) {
            return;
        }
        EvidenceRequirement requirement = requirements
                .findByApplicationIdAndType(application.getId(), RequirementType.IDENTITY)
                .orElse(null);
        if (requirement == null) {
            return;
        }

        if (!result.answered()) {
            // No answer was obtained. Recorded and visible, never a decline.
            requirement.markUnavailable();
        } else if (result.needsAnotherDocument()) {
            requirement.needEvidence();
        } else {
            // Including bad news: a fraud verdict is an answer, and judging it is the ruleset's job.
            requirement.receive(new EvidenceSource.Vendor(result.checkId()));
        }
        requirements.save(requirement);

        auditLog.record(AuditEntry.bySystem(application.getId(), AuditEventType.REQUIREMENT_CHANGED,
                Map.of("type", requirement.getType(), "status", requirement.getStatus(),
                        "vendorCheckId", result.checkId(),
                        "outcome", result.outcome() == null ? "" : result.outcome(),
                        "failureCode", result.failureCode() == null ? "" : result.failureCode())));
    }
}
