package com.mettyoung.creditcardapplication.audit;

/**
 * What happened. One constant per recorded fact; the payload carries ids and codes, never declared data.
 */
public enum AuditEventType {
    /**
     * The application's durable workflow instance began. The first row of the <em>workflow</em> - uploads are
     * recorded before it, because they happen before submit.
     */
    WORKFLOW_STARTED,
    UPLOAD_REQUESTED,
    DOCUMENT_VERIFIED,
    STATUS_CHANGED,
    REQUIREMENT_CHANGED,
    VENDOR_CHECK_QUEUED,
    VENDOR_CHECK_COMPLETED,
    VENDOR_CHECK_FAILED,
    WEBHOOK_RECEIVED,
    /** Approved, referred or declined - by the system or a reviewer; the payload says which (FR8). */
    DECISION_MADE,
    /** A vendor call failed in a way worth retrying, and attempts remain: which attempt, why, and when next (FR11). */
    VENDOR_CHECK_RETRY,
    /** A retry found the check a lost attempt had already created, and adopted it instead of paying twice (FR11). */
    VENDOR_CHECK_ADOPTED,
    /** A retry looked for a check an earlier attempt created and found none, so creating one was safe (FR11.6). */
    VENDOR_CHECK_LOOKUP_EMPTY
}
