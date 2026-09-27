package com.mettyoung.creditcardapplication.audit.internal;

import com.mettyoung.creditcardapplication.audit.Actor;
import com.mettyoung.creditcardapplication.audit.AuditEventType;
import com.mettyoung.creditcardapplication.shared.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per recorded fact, written in the same transaction as the change it records. Append-only: the
 * database refuses an UPDATE or DELETE (see {@code V6__audit_event.sql}), so the application that writes the
 * log cannot edit it.
 * <p>
 * {@code seq} is per application and contiguous, so a missing row is visible rather than silent.
 */
@Getter
@Entity
@Table(name = "audit_event")
class AuditEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "seq", nullable = false, updatable = false)
    private long seq;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private AuditEventType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor", nullable = false, updatable = false)
    private Actor actor;

    @Column(name = "actor_id", updatable = false)
    private String actorId;

    // jsonb, not text: the column is queryable by a later increment, and Hibernate needs telling,
    // because a bare String would otherwise be validated against varchar.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "at", nullable = false, updatable = false)
    private Instant at;

    protected AuditEvent() {
        // for JPA
    }

    private AuditEvent(UUID applicationId, long seq, AuditEventType type, Actor actor, String actorId,
                       String payload, Instant at) {
        this.id = UuidV7.generate();
        this.applicationId = applicationId;
        this.seq = seq;
        this.type = type;
        this.actor = actor;
        this.actorId = actorId;
        this.payload = payload;
        this.at = at;
    }

    public static AuditEvent of(UUID applicationId, long seq, AuditEventType type, Actor actor, String actorId,
                                String payload, Instant at) {
        return new AuditEvent(applicationId, seq, type, actor, actorId, payload, at);
    }
}
