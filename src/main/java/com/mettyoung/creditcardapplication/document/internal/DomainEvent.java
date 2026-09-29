package com.mettyoung.creditcardapplication.document.internal;

import java.util.UUID;

/**
 * What the aggregate recorded about itself, for {@link DocumentEffects} to act on.
 * <p>
 * Internal to the module and separate from {@code shared.outbox.DomainEvent}: only one of these becomes an
 * outbox row, and the verdict needs a reason that has no business in a durable payload other modules read.
 */
sealed interface DomainEvent {

    UUID documentId();

    /** An upload URL was issued. Worth an audit row; nothing else can act on it yet. */
    record UploadRequested(UUID documentId) implements DomainEvent {
    }

    /**
     * The object was ruled on.
     *
     * @param reason why it was rejected, or null when it was accepted. Carried here because it is computed
     *               from the stored object rather than held on the row.
     */
    record Settled(UUID documentId, String reason) implements DomainEvent {
    }
}
