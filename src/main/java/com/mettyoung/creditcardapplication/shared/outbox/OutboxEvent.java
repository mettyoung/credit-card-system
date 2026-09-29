package com.mettyoung.creditcardapplication.shared.outbox;

import com.mettyoung.creditcardapplication.shared.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * An event committed in the same transaction as the state change that produced it. That shared commit is the
 * whole point: there is no window in which the change happened and the event did not.
 */
@Getter
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    // jsonb, not text: the column is queryable by a later increment, and Hibernate needs telling,
    // because a bare String would otherwise be validated against varchar.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Column(name = "claimed_until")
    private Instant claimedUntil;

    protected OutboxEvent() {
        // for JPA
    }

    private OutboxEvent(UUID id, UUID applicationId, String type, String payload, Instant createdAt) {
        this.id = id;
        this.applicationId = applicationId;
        this.type = type;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public static OutboxEvent of(DomainEvent event, String payload, Instant now) {
        return new OutboxEvent(UuidV7.generate(), event.applicationId(), event.type(), payload, now);
    }

    public void markPublished(Instant now) {
        publishedAt = now;
        // Nothing will select it again, so the claim has nothing left to protect.
        claimedBy = null;
        claimedUntil = null;
    }
}
