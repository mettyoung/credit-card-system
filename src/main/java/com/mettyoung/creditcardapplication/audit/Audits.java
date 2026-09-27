package com.mettyoung.creditcardapplication.audit;

/**
 * The audit module's API, and the only way in. The row, its sequence and the append-only guarantee stay
 * inside the module; a caller outside it can name this interface, {@link AuditEntry}, {@link AuditEventType}
 * and {@link Actor}, and nothing else.
 * <p>
 * Writes use the caller's transaction on purpose, so a change that committed is a change that was logged.
 */
public interface Audits {

    /**
     * @throws org.springframework.transaction.IllegalTransactionStateException if there is no transaction to
     *                                                                         join — see the implementation
     */
    void record(AuditEntry entry);
}
