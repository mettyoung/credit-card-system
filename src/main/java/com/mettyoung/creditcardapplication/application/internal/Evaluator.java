package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.RequirementStatus;
import com.mettyoung.creditcardapplication.application.RequirementType;
import com.mettyoung.creditcardapplication.application.ApplicationStatus;

import java.util.List;

/**
 * The decision, as a pure function: no I/O, no clock, no randomness. That is what lets the whole table of
 * outcomes be a unit test rather than an integration test, and it is where most of this increment's and FR5's
 * coverage lives.
 * <p>
 * Nothing here mutates anything. {@link ApplicationProcess} applies whatever this returns.
 */
final class Evaluator {

    private Evaluator() {
    }

    /**
     * @param requirements every requirement of the application, whatever its state
     */
    public static NextStep evaluate(Application application, List<EvidenceRequirement> requirements) {
        // Intake is recorded and nothing has been asked yet. FR5 plugs the first check in here; until it does,
        // a spine with no checks attached correctly has nothing to start.
        if (application.getStatus() == ApplicationStatus.SUBMITTED) {
            return new NextStep.Wait();
        }

        List<RequirementType> needingEvidence = requirements.stream()
                .filter(requirement -> requirement.getStatus() == RequirementStatus.NEEDS_EVIDENCE)
                .map(EvidenceRequirement::getType)
                .toList();
        if (!needingEvidence.isEmpty()) {
            return new NextStep.RequestInfo(needingEvidence);
        }

        // An application with no requirements is not finished, it is un-started. Completing on an empty list
        // would make a missing row look like a settled one.
        boolean allSettled = !requirements.isEmpty()
                && requirements.stream().allMatch(EvidenceRequirement::isSettled);
        return allSettled ? new NextStep.Complete() : new NextStep.Wait();
    }
}
