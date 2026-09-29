package com.mettyoung.creditcardapplication.document.internal;

import com.mettyoung.creditcardapplication.document.ContentType;
import com.mettyoung.creditcardapplication.document.DocumentKind;
import com.mettyoung.creditcardapplication.document.DocumentNotFoundException;
import com.mettyoung.creditcardapplication.document.DocumentStatus;
import com.mettyoung.creditcardapplication.document.DocumentVerified;
import com.mettyoung.creditcardapplication.document.Documents;
import com.mettyoung.creditcardapplication.document.InvalidDocumentException;
import com.mettyoung.creditcardapplication.document.RequestUploadCommand;
import com.mettyoung.creditcardapplication.document.Sha256;
import com.mettyoung.creditcardapplication.document.UploadIncompleteException;
import com.mettyoung.creditcardapplication.document.Uploads;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Upload URLs and the verdict on what was uploaded.
 * <p>
 * Package-private: {@link Documents} and {@link Uploads} are the module's API, and this is the only
 * implementation of them. A caller outside this package cannot name the class even to hold a reference, so
 * the facade is enforced by the compiler and not only by {@code ModuleStructureTest}.
 * <p>
 * The bytes never come through here. The client PUTs them straight to the object store; this class issues a
 * URL bound to the content type, the length and the checksum, then verifies the stored object against the
 * same three claims.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class DocumentService implements Documents, Uploads {

    private final DocumentRepository repository;
    private final ObjectStore objectStore;
    private final StorageProperties storage;
    private final Clock clock;

    /**
     * @throws InvalidDocumentException if a declared attribute is invalid
     */
    @Override
    @Transactional
    public Upload requestUpload(UUID applicationId, String userId, RequestUploadCommand request) {
        // No parsing here. Every field arrived as the value object it has to be, validated by its own
        // constructor when the body was bound, so this method has nothing left to check.
        Document.Owner owner = new Document.Owner(applicationId, userId);
        Document document = Document.requestUpload(owner, request, Instant.now(clock));
        repository.save(document);

        var constraints = new ObjectStore.Constraints(request.contentType(), request.sizeBytes(),
                base64(request.sha256()));
        var presigned = objectStore.presignUpload(document.getObjectKey(), constraints,
                storage.uploadUrlValidFor());

        return new Upload(document.getId(), presigned.url(), presigned.method(),
                presigned.requiredHeaders(), presigned.expiresAt());
    }

    /**
     * Verifies the object against every declared attribute and records the verdict. Idempotent: replaying it
     * re-reads the object and reaches the same answer.
     *
     * @throws DocumentNotFoundException if no such document belongs to this applicant
     * @throws UploadIncompleteException if no object was ever uploaded
     */
    @Override
    @Transactional
    public DocumentVerified completeUpload(UUID applicationId, UUID documentId, String userId) {
        Document document = repository.findByIdAndApplicationIdAndUserId(documentId, applicationId, userId)
                .orElseThrow(DocumentNotFoundException::new);

        ObjectStore.StoredObject stored = objectStore.head(document.getObjectKey())
                .orElseThrow(() -> new UploadIncompleteException(document.getStatus()));

        String reason = verify(document, stored);
        if (reason == null) {
            document.markUploaded();
        } else {
            document.markInvalid(reason);
        }
        repository.save(document);

        // Saving publishes what the aggregate recorded; DocumentEffects writes the audit row, and the
        // outbox row when the verdict was an acceptance, from inside this transaction.
        return new DocumentVerified(document.getId(), applicationId,
                document.getKind(), document.getStatus(), reason);
    }

    /**
     * @throws DocumentNotFoundException if no such document belongs to this applicant
     */
    @Override
    @Transactional(readOnly = true)
    public DocumentVerified get(UUID applicationId, UUID documentId, String userId) {
        Document document = repository.findByIdAndApplicationIdAndUserId(documentId, applicationId, userId)
                .orElseThrow(DocumentNotFoundException::new);
        // The entity stays inside the module: a reader only needs the verdict, not the object key.
        return new DocumentVerified(document.getId(), applicationId,
                document.getKind(), document.getStatus(), null);
    }

    /**
     * Whether the application holds an accepted document of this kind — the submit precondition.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean hasAccepted(UUID applicationId, DocumentKind kind) {
        return repository.existsByApplicationIdAndKindAndStatus(applicationId, kind, DocumentStatus.UPLOADED);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Accepted> ofApplication(UUID applicationId) {
        return repository.findByApplicationIdOrderByIdDesc(applicationId).stream()
                .map(document -> new Accepted(document.getId(), document.getKind(), document.getStatus(),
                        document.getCreatedAt()))
                .toList();
    }

    /**
     * Reads the whole object. The one place a document's bytes pass through the application; they are never
     * logged, never written to disk, and never put in an audit payload.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Content> contentFor(UUID documentId) {
        return repository.findById(documentId)
                // I11 enforced here rather than at the caller, so no caller can forget it.
                .filter(Document::isSendable)
                .flatMap(document -> objectStore.readHead(document.getObjectKey(), (int) document.getSizeBytes())
                        .map(bytes -> new Content(document.getId(), document.getContentType().mediaType(),
                                document.getContentType().fileExtension(), bytes)));
    }

    /**
     * Why the object was rejected, or {@code null} when it matched everything.
     * <p>
     * Three separate claims, checked in the order that costs least: the length comes back on the HEAD, the
     * checksum comes from the store's own digest, and only then are the first bytes read.
     */
    private String verify(Document document, ObjectStore.StoredObject stored) {
        if (stored.sizeBytes() != document.getSizeBytes()) {
            return "size-mismatch";
        }
        String expected = base64(document.getSha256());
        if (stored.sha256Base64() == null || !constantTimeEquals(stored.sha256Base64(), expected)) {
            return "checksum-mismatch";
        }
        byte[] head = objectStore.readHead(document.getObjectKey(), ContentType.magicBytesToRead())
                .orElse(new byte[0]);
        if (!document.getContentType().matchesMagicBytes(head)) {
            // A declared content type is a claim; the leading bytes are the evidence.
            return "content-type-mismatch";
        }
        return null;
    }

    /**
     * S3 carries the digest base64-encoded; we hold it as hex, which is what a client can read back.
     */
    private static String base64(Sha256 sha256) {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha256.value()));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < a.length(); i++) {
            difference |= a.charAt(i) ^ b.charAt(i);
        }
        return difference == 0;
    }

}
