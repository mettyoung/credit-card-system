package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.RequirementType;

import java.util.List;

/**
 * What the orchestrator decided to do next. Sealed, so applying a step is an exhaustive switch and a new kind
 * of step cannot be added without every caller saying what it means - which is how FR5 adds
 * {@code StartIdentityCheck} without any branch being able to quietly ignore it.
 */
public sealed interface NextStep {

    /** Nothing to do: something else is still outstanding. */
    record Wait() implements NextStep {
    }

    /** Ask the applicant for more evidence. */
    record RequestInfo(List<RequirementType> missing) implements NextStep {
    }

    /** Every requirement is settled. */
    record Complete() implements NextStep {
    }
}
