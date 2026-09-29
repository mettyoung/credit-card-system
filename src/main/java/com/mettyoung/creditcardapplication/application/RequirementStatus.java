package com.mettyoung.creditcardapplication.application;

/**
 * A requirement says whether an answer <em>arrived</em>. Whether the answer is good news is nobody's call in
 * this scope — which is why a fraud verdict is RECEIVED, not FAILED.
 */
public enum RequirementStatus {
    PENDING,
    /** Its check answered, whatever the answer was. */
    RECEIVED,
    /** The applicant must upload something before this can go further. */
    NEEDS_EVIDENCE,
    /** No answer was obtained: retries or the deadline ran out. Recorded, never treated as a result. */
    UNAVAILABLE;

    /** Satisfies the CHECKS_COMPLETE guard — answered, or known to be unanswerable. */
    public boolean isSettled() {
        return this == RECEIVED || this == UNAVAILABLE;
    }
}
