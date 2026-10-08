package com.mettyoung.creditcardapplication.application;

/**
 * Why an application was referred, or why a reviewer declined it (FR8). A code, never free text, so nothing
 * personal can reach the audit log through it - and never shown to the applicant, since "suspected fraud" is not
 * something to tell the suspect.
 */
public enum DecisionReason {
    /** System: identity answered FRAUD. */
    FRAUD_SUSPECTED(Setter.SYSTEM),
    /** System: identity is UNAVAILABLE - the vendor never answered, which must not become a decline. */
    EVIDENCE_UNAVAILABLE(Setter.SYSTEM),
    /** Reviewer: the fraud is confirmed. */
    FRAUD_CONFIRMED(Setter.REVIEWER),
    /** Reviewer: identity could not be confirmed. */
    IDENTITY_NOT_ESTABLISHED(Setter.REVIEWER),
    /** Reviewer: another reason, recorded outside this system. */
    POLICY(Setter.REVIEWER);

    private final Setter setter;

    DecisionReason(Setter setter) {
        this.setter = setter;
    }

    /** A system reason explains a referral; only a reviewer's reason may justify a decline. */
    public boolean isReviewerReason() {
        return setter == Setter.REVIEWER;
    }

    private enum Setter {
        SYSTEM,
        REVIEWER
    }
}
