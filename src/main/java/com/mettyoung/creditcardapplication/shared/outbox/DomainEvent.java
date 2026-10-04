package com.mettyoung.creditcardapplication.shared.outbox;

import java.util.UUID;

/**
 * Everything the orchestrator reacts to. Sealed, so the relay's dispatch is exhaustive and a new event
 * breaks the build until it is handled.
 * <p>
 * Payloads carry ids only. They are serialised into the outbox, which is a durable log read by code that
 * may be a version behind — a name or a date of birth in here would be both a privacy leak and a
 * compatibility problem.
 */
public sealed interface DomainEvent {

    UUID applicationId();

    /** The name stored in {@code outbox.type}; the relay maps it back. */
    default String type() {
        return getClass().getSimpleName();
    }

    record ApplicationSubmitted(UUID applicationId) implements DomainEvent {
    }

    record DocumentUploaded(UUID applicationId, UUID documentId, String kind) implements DomainEvent {
    }

    record VendorCheckCompleted(UUID applicationId, UUID vendorCheckId) implements DomainEvent {
    }

    /** The seam the decisioning increment subscribes to. Nothing consumes it yet. */
    record ChecksCompleted(UUID applicationId) implements DomainEvent {
    }
}
