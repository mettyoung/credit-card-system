package com.mettyoung.creditcardapplication.vendor.internal;

enum CheckStatus {
    QUEUED,
    /** Claimed by a worker, which holds a lease that expires if it dies. */
    IN_PROGRESS,
    /** Waiting for the next attempt after a retryable failure. */
    RETRY,
    /** The async vendor accepted the work and will call back. */
    AWAITING_CALLBACK,
    /** The vendor gave an answer — which may itself be bad news. */
    COMPLETED,
    /** No answer was obtained: attempts or the deadline ran out. */
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
