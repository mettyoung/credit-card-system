package com.mettyoung.creditcardapplication.document.internal;

import java.util.UUID;

/**
 * Where the bytes live in the object store. Derived from ids, never from anything the client supplies, so a
 * caller cannot steer a write at another application's prefix or escape the bucket with {@code ../}.
 */
record ObjectKey(String value) {

    public ObjectKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
    }

    public static ObjectKey forDocument(UUID applicationId, UUID documentId) {
        return new ObjectKey("applications/" + applicationId + "/documents/" + documentId);
    }
}
