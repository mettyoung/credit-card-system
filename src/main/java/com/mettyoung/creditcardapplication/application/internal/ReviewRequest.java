package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.DecisionReason;
import jakarta.validation.constraints.NotNull;

/**
 * A reviewer's decision. Shape only here; whether a reason is required, and which, is the aggregate's rule.
 *
 * @param reason  required to decline, ignored to approve
 * @param version the stale-copy precondition, as on {@code PATCH}
 */
record ReviewRequest(@NotNull Outcome outcome, DecisionReason reason, @NotNull Long version) {

    enum Outcome {
        APPROVED,
        DECLINED
    }
}
