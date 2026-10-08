package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.DecisionReason;
import com.mettyoung.creditcardapplication.application.RequirementStatus;
import com.mettyoung.creditcardapplication.application.RequirementType;

import java.util.List;

/**
 * FR8.1: the smallest safe decision, as a pure function. Approve a clean result, refer everything else; never
 * decline - an outage or a fraud flag is for a person to judge, and only a person may decline (README:
 * Compliance). Refer wins over approve, and the first matching rule names the reason.
 * <p>
 * Not a rule engine: a fixed table, which is what a later increment's versioned ruleset replaces. A check type
 * FR7 adds brings its own flagged outcomes here (a sanctions hit refers too).
 */
final class DecisionRules {

    /** The vendor module's name for a suspected forgery (FR5's IdvOutcome), as CheckResult reports it. */
    static final String FRAUD_OUTCOME = "FRAUD";

    private DecisionRules() {
    }

    /**
     * One requirement as the decision sees it.
     *
     * @param outcome the answering check's outcome; {@code null} when no check answered
     */
    record Evidence(RequirementType type, RequirementStatus status, String outcome) {
    }

    sealed interface Decision {

        record Approve() implements Decision {
        }

        record Refer(DecisionReason reason) implements Decision {
        }
    }

    static Decision decide(List<Evidence> evidence) {
        // Nothing to approve on is not a clean result. Unreachable - CHECKS_COMPLETE needs a settled requirement
        // - but an empty list must never read as "nothing was wrong".
        if (evidence.isEmpty()
                || evidence.stream().anyMatch(item -> item.status() == RequirementStatus.UNAVAILABLE)) {
            return new Decision.Refer(DecisionReason.EVIDENCE_UNAVAILABLE);
        }
        if (evidence.stream().anyMatch(item -> item.type() == RequirementType.IDENTITY
                && FRAUD_OUTCOME.equals(item.outcome()))) {
            return new Decision.Refer(DecisionReason.FRAUD_SUSPECTED);
        }
        if (evidence.stream().allMatch(item -> item.status() == RequirementStatus.RECEIVED)) {
            return new Decision.Approve();
        }
        // Settled but neither answered nor unavailable cannot happen; refer rather than guess.
        return new Decision.Refer(DecisionReason.EVIDENCE_UNAVAILABLE);
    }
}
