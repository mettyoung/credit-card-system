package com.mettyoung.creditcardapplication.document.internal;

import com.mettyoung.creditcardapplication.document.ContentType;
import com.mettyoung.creditcardapplication.document.DocumentAlreadySettledException;
import com.mettyoung.creditcardapplication.document.InvalidDocumentException;
import com.mettyoung.creditcardapplication.document.Sha256;
import com.mettyoung.creditcardapplication.document.DocumentKind;
import com.mettyoung.creditcardapplication.document.DocumentStatus;
import com.mettyoung.creditcardapplication.document.RequestUploadCommand;
import com.mettyoung.creditcardapplication.shared.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import org.springframework.data.domain.AbstractAggregateRoot;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An uploaded file, and the verdict on it. Its own aggregate: a document is verified, expired and read
 * without loading or locking the application it belongs to.
 * <p>
 * No setters: the three verdicts below are the only transitions, and each is one-way.
 */
@Getter
@Entity
@Table(name = "document")
/**
 * Extends {@link AbstractAggregateRoot} for the event plumbing only: {@code registerEvent} records what a
 * call did, Spring Data publishes it when the repository saves, and clears it afterwards so a second save
 * cannot publish it twice.
 */
class Document extends AbstractAggregateRoot<Document> {

    /** How long a pre-signed URL stays usable before the row is swept (FR3 §5.3). */
    public static final int MAX_SIZE_BYTES = 10 * 1024 * 1024;

    static final String SIZE_FIELD = "sizeBytes";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private DocumentKind kind;

    @Convert(converter = ObjectKeyConverter.class)
    @Column(name = "object_key", nullable = false, updatable = false)
    private ObjectKey objectKey;

    @Convert(converter = Sha256Converter.class)
    @Column(name = "sha256", nullable = false, updatable = false, length = Sha256.HEX_LENGTH)
    private Sha256 sha256;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, updatable = false)
    private ContentType contentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DocumentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Wrapper type on purpose: Spring Data reads a null version as "new" and calls persist.
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Document() {
        // for JPA
    }

    private Document(UUID id, UUID applicationId, String userId, DocumentKind kind, ObjectKey objectKey,
                     Sha256 sha256, long sizeBytes, ContentType contentType, Instant createdAt) {
        this.id = id;
        this.applicationId = applicationId;
        this.userId = userId;
        this.kind = kind;
        this.objectKey = objectKey;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.contentType = contentType;
        this.createdAt = createdAt;
        this.status = DocumentStatus.PENDING_UPLOAD;
    }

    /** Who the document belongs to. The pair every lookup is scoped by, so it travels as one. */
    public record Owner(UUID applicationId, String userId) {
    }

    /**
     * @throws InvalidDocumentException if the request size is outside the allowed range
     */
    public static Document requestUpload(Owner owner, RequestUploadCommand request, Instant now) {
        Objects.requireNonNull(owner.applicationId(), "applicationId");
        Objects.requireNonNull(owner.userId(), "userId");
        Objects.requireNonNull(request.kind(), "kind");
        long sizeBytes = request.sizeBytes();
        if (sizeBytes <= 0) {
            throw new InvalidDocumentException(SIZE_FIELD, SIZE_FIELD + " must be greater than zero.");
        }
        if (sizeBytes > MAX_SIZE_BYTES) {
            throw new InvalidDocumentException(SIZE_FIELD, SIZE_FIELD + " must be at most " + MAX_SIZE_BYTES + ".");
        }
        UUID id = UuidV7.generate();
        Document document = new Document(id, owner.applicationId(), owner.userId(),
                request.kind(), ObjectKey.forDocument(owner.applicationId(), id),
                request.sha256(), sizeBytes, request.contentType(), now);
        document.registerEvent(new DomainEvent.UploadRequested(id));
        return document;
    }

    /** The object matched every declared attribute. */
    public void markUploaded() {
        requirePendingOrSame(DocumentStatus.UPLOADED);
        status = DocumentStatus.UPLOADED;
        registerEvent(new DomainEvent.Settled(id, null));
    }

    /**
     * The object is there but disagrees with what was declared. A verdict, not an error: the request was
     * well-formed and this is the answer.
     */
    public void markInvalid(String reason) {
        requirePendingOrSame(DocumentStatus.INVALID);
        status = DocumentStatus.INVALID;
        // The reason travels with the fact rather than being stored: it describes this verdict, and the row
        // already carries the verdict itself.
        registerEvent(new DomainEvent.Settled(id, reason));
    }

    /** Swept because the upload never completed. Only an upload still waiting for bytes can expire. */
    public void markExpired() {
        if (status != DocumentStatus.PENDING_UPLOAD) {
            throw new DocumentAlreadySettledException(status);
        }
        status = DocumentStatus.EXPIRED;
    }

    /** I11: only a verified object may answer a requirement or be sent to a vendor. */
    public boolean isSendable() {
        return status == DocumentStatus.UPLOADED;
    }

    /**
     * No caller, here or in any later increment - the sweep filters on the status in its query instead.
     * Private so it cannot be mistaken for part of the aggregate's API; delete it if it is still unused when
     * something finally needs to ask.
     */
    @SuppressWarnings("unused")
    private boolean isPendingUpload() {
        return status == DocumentStatus.PENDING_UPLOAD;
    }

    /**
     * {@code /complete} is idempotent: replaying it re-asserts the verdict already reached. Only a status
     * that could never lead here is refused.
     */
    /**
     * A verdict may be reached once, or replayed to the same answer. Anything else is an attempt to overturn
     * one, which is refused whatever the new answer would have been.
     */
    private void requirePendingOrSame(DocumentStatus target) {
        if (status != DocumentStatus.PENDING_UPLOAD && status != target) {
            throw new DocumentAlreadySettledException(status);
        }
    }
}
