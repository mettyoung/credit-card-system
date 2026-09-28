package com.mettyoung.creditcardapplication.document;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.UUID;

/**
 * The verdict on a document: what the service hands to the audit log without either reading the entity again,
 * and what the endpoints answer with.
 * <p>
 * One type rather than a near-identical response record beside it. What the wire should not carry is said
 * here, next to the field, instead of being enforced by copying four values into a second shape.
 *
 * @param reason why an object was rejected, or null. A code, never a value read out of the file.
 */
public record DocumentVerified(UUID documentId,
                               // Already in the path of every endpoint that returns this, so repeating it in
                               // the body is noise. Still needed by callers inside the module.
                               @JsonIgnore UUID applicationId,
                               DocumentKind kind, DocumentStatus status, String reason) {

    /**
     * No caller yet: FR4 uses it to decide whether a verdict is worth an outbox event, since only an accepted
     * document can move a requirement forward. Public because the caller is in another package, and
     * {@code @JsonIgnore} because a derived accessor would otherwise appear on the wire as {@code uploaded}.
     */
    @JsonIgnore
    public boolean isUploaded() {
        return status == DocumentStatus.UPLOADED;
    }
}
