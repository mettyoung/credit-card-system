package com.mettyoung.creditcardapplication.document;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules read about a document — the other half of the pair {@link Uploads} writes through.
 * <p>
 * Repositories and the object store stay inside. Other modules asked for {@code DocumentRepository} and
 * {@code ObjectStore} directly before this existed, which coupled the workflow and the vendor adapter to how
 * documents are stored, and made the module boundary unenforceable.
 */
public interface Documents {

    /**
     * Newest first. Enough for the orchestrator to decide whether fresh evidence has arrived.
     * <p>
     * No caller yet: FR4's {@code ApplicationProcess} is the one this exists for. Kept rather than deferred
     * because the read is the module's half of that contract, and writing it with the module is what stops
     * the orchestrator reaching for {@code DocumentRepository} instead.
     */
    List<Accepted> ofApplication(UUID applicationId);

    /** No caller yet: FR4's {@code ApplicationService.submit} asks this before it will accept a submission. */
    boolean hasAccepted(UUID applicationId, DocumentKind kind);

    /**
     * The bytes, for sending to a vendor. Empty unless the document is accepted — I11: only a verified object
     * may be sent, enforced here rather than trusted to the caller.
     * <p>
     * No caller yet: FR5's Onfido adapter posts these as the multipart body of {@code POST /documents}. It
     * cannot be package-private or hidden — a different module is the entire point of it — so the guard is
     * {@code DocumentBytesTest}, which keeps the web layer from calling it or even naming {@link Content}.
     */
    Optional<Content> contentFor(UUID documentId);

    /**
     * A document as other modules need to see it: what it is and when it arrived, never where its bytes live.
     */
    record Accepted(UUID id, DocumentKind kind, DocumentStatus status, Instant createdAt) {

        /** I11: only a verified object may answer a requirement or be sent to a vendor. */
        public boolean isSendable() {
            return status == DocumentStatus.UPLOADED;
        }
    }

    /**
     * @param mediaType     the verified type, as a string — the allow-list enum stays inside the module, so a
     *                      caller cannot branch on it and quietly grow a second copy of the rules
     * @param fileExtension what to call the file when handing it to a vendor
     */
    record Content(UUID documentId, String mediaType, String fileExtension, byte[] bytes) {
    }
}
