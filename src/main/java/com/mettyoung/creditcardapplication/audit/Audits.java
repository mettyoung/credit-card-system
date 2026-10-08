package com.mettyoung.creditcardapplication.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /**
     * FR11: the events recorded for one application after {@code afterSeq}, in order - the log's first reader.
     * Narrow on purpose: one application, read forward, for the development timeline.
     */
    List<RecordedEvent> since(UUID applicationId, long afterSeq);

    /** A recorded event as a reader sees it. No actor id: who acted is the actor's kind, not their identity. */
    record RecordedEvent(long seq, AuditEventType type, Actor actor, Map<String, Object> payload, Instant at) {
    }
}
