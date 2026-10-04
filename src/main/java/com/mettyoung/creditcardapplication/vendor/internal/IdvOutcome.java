package com.mettyoung.creditcardapplication.vendor.internal;

/**
 * What the IDV vendor said, in our words rather than theirs. The adapter maps Onfido's
 * {@code result}/{@code sub_result} pair onto this, and nothing outside the adapter package sees the
 * original vocabulary.
 */
enum IdvOutcome {
    /** Document genuine, data extracted. */
    VERIFIED,
    /** Suspected forgery. An answer, so the requirement is satisfied; acting on it is the ruleset's job. */
    FRAUD,
    /** Unusable image. The applicant must upload another. */
    UNREADABLE;

    /** Whether the applicant has to do something before this requirement can move on. */
    public boolean needsAnotherDocument() {
        return this == UNREADABLE;
    }
}
