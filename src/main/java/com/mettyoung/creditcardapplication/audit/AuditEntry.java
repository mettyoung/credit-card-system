package com.mettyoung.creditcardapplication.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One fact to record. A parameter object rather than five arguments, because they are not five values that
 * happen to arrive together — they are one entry, and a caller that got two of them in the wrong order would
 * have been writing a plausible-looking lie.
 * <p>
 * Built through {@link #byApplicant} or {@link #bySystem}, so who caused the event and whether an actor id
 * belongs with it are decided in one place instead of at every call.
 *
 * @param payload ids, codes and statuses only — never declared data. The log is evidence that something
 *                happened, not a second copy of the applicant.
 */
public record AuditEntry(UUID applicationId, AuditEventType type, Actor actor, String actorId,
                         Map<String, Object> payload) {

    public AuditEntry {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(actor, "actor");
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    /** Something the applicant did. */
    public static AuditEntry byApplicant(UUID applicationId, AuditEventType type, String actorId,
                                         Map<String, Object> payload) {
        return new AuditEntry(applicationId, type, Actor.APPLICANT, actorId, payload);
    }

    /** Something the system did on its own — a worker, a poller, the orchestrator. No actor id to carry. */
    public static AuditEntry bySystem(UUID applicationId, AuditEventType type, Map<String, Object> payload) {
        return new AuditEntry(applicationId, type, Actor.SYSTEM, null, payload);
    }
}
