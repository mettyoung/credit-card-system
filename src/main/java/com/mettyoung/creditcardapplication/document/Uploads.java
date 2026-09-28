package com.mettyoung.creditcardapplication.document;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Issuing an upload URL and ruling on what arrived — the module's API for whoever drives an upload.
 * <p>
 * One of the module's two facades, split by direction: this is what a module writes through, {@link Documents}
 * is what it reads through. Two types rather than one because Spring Modulith can say which types a module
 * may touch but not which methods — so a reader that must not upload is a separate interface or it is only a
 * convention. {@code vendor} reads a document's bytes to send to a checker; it has no business requesting one.
 */
public interface Uploads {

    Upload requestUpload(UUID applicationId, String userId, RequestUploadCommand request);

    /** Idempotent: replaying it re-reads the object and reaches the same verdict. */
    DocumentVerified completeUpload(UUID applicationId, UUID documentId, String userId);

    DocumentVerified get(UUID applicationId, UUID documentId, String userId);

    /**
     * @param requiredHeaders the client must send these on the PUT, or the store rejects the bytes — the
     *                        checksum and content type are signed into the URL
     */
    record Upload(UUID documentId, URI url, String method,
                  Map<String, String> requiredHeaders, Instant expiresAt) {
    }
}
