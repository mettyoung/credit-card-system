package com.mettyoung.creditcardapplication.shared.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Table;
import lombok.Getter;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per (consumer, event). Inserting it is how a consumer claims an event exactly once: the primary
 * key refuses the second attempt, so a redelivery is a no-op rather than a duplicate effect.
 */
@Getter
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @EmbeddedId
    private Key key;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // for JPA
    }

    public ProcessedEvent(String consumer, UUID eventId, Instant processedAt) {
        this.key = new Key(consumer, eventId);
        this.processedAt = processedAt;
    }

    @Embeddable
    @Getter
    public static class Key implements Serializable {

        @Column(name = "consumer", nullable = false, updatable = false)
        private String consumer;

        @Column(name = "event_id", nullable = false, updatable = false)
        private UUID eventId;

        protected Key() {
            // for JPA
        }

        Key(String consumer, UUID eventId) {
            this.consumer = consumer;
            this.eventId = eventId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && consumer.equals(key.consumer)
                    && eventId.equals(key.eventId);
        }

        @Override
        public int hashCode() {
            return consumer.hashCode() * 31 + eventId.hashCode();
        }
    }
}
