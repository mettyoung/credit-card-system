package com.mettyoung.creditcardapplication.application;

/**
 * Stored as {@code text}, so adding a constant needs no migration — which is why FR1 avoided a Postgres
 * enum type.
 */
public enum ApplicationStatus {
    /** Declared data is editable; nothing has been checked. */
    DRAFT,
    /** Intake is recorded. The orchestrator has not yet queued the checks. */
    SUBMITTED,
    /** Checks are outstanding. */
    VERIFYING,
    /** A requirement needs a document from the applicant before it can go further. */
    NEEDS_INFO,
    /**
     * Every requirement is answered or recorded unavailable, and every raw vendor response is stored. Carries
     * no verdict; the decision follows in its own transaction (FR8).
     */
    CHECKS_COMPLETE,
    /** Terminal. Approved by the system on a clean result, or by a reviewer. */
    APPROVED,
    /** Waiting for a reviewer: the system never declines (FR8.1), so anything not clean comes here. */
    REFERRED,
    /** Terminal. Only a reviewer declines, and only with a reason. */
    DECLINED;

    /** Uploading evidence only makes sense while the application is still gathering it. */
    public boolean acceptsUploads() {
        return this == DRAFT || this == NEEDS_INFO;
    }
}
