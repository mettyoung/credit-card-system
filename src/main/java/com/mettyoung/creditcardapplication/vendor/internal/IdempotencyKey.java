package com.mettyoung.creditcardapplication.vendor.internal;

import java.util.UUID;

/**
 * What stops a retry becoming a second paid call. For IDV the key includes the document, because a re-upload
 * is a genuinely new check rather than a retry of the old one.
 */
record IdempotencyKey(String value) {

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }

    public static IdempotencyKey forIdv(UUID applicationId, UUID documentId) {
        return new IdempotencyKey(applicationId + ":IDV:" + documentId);
    }
}
