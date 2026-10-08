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
    DECISION_MADE
}
