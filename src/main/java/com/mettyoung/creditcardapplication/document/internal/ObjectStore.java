package com.mettyoung.creditcardapplication.document.internal;

import com.mettyoung.creditcardapplication.document.ContentType;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * The object store, as this application needs it. A port so the S3 SDK stays in one adapter and the service
 * can be reasoned about without it.
 */
interface ObjectStore {

    /**
     * A URL the client may PUT to exactly once, bound to the content type, the length and the checksum, so
     * the store itself rejects bytes that disagree with what was declared.
     */
    PresignedUpload presignUpload(ObjectKey key, Constraints constraints, Duration validFor);

    /** Empty when the object is not there. */
    Optional<StoredObject> head(ObjectKey key);

    /** The first {@code count} bytes, for the magic-byte check. Empty when the object is not there. */
    Optional<byte[]> readHead(ObjectKey key, int count);

    void delete(ObjectKey key);

    /** What the client declared the object would be. All three are signed into the URL together. */
    record Constraints(ContentType contentType, long sizeBytes, String sha256Base64) {
    }

    record PresignedUpload(URI url, String method, Map<String, String> requiredHeaders, Instant expiresAt) {
    }

    record StoredObject(long sizeBytes, String sha256Base64) {
    }
}
