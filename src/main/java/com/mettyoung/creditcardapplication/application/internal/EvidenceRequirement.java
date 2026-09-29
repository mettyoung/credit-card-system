package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.RequirementStatus;
import com.mettyoung.creditcardapplication.application.RequirementType;
import com.mettyoung.creditcardapplication.shared.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.util.UUID;

/**
 * One row per question asked of an application. Its own aggregate, not a {@code @OneToMany} on
 * {@link Application}: a vendor result updates one requirement without loading or locking its siblings.
 * <p>
 * The source is stored as two columns rather than a serialised {@link EvidenceSource}, so a query can ask
 * "which check answered this" without parsing JSON.
 */
@Getter
@Entity
@Table(name = "evidence_requirement")
class EvidenceRequirement {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private RequirementType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RequirementStatus status;

    @Column(name = "source_vendor_check_id")
    private UUID sourceVendorCheckId;

    @Column(name = "source_document_id")
    private UUID sourceDocumentId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected EvidenceRequirement() {
        // for JPA
    }

    private EvidenceRequirement(UUID applicationId, RequirementType type) {
        this.id = UuidV7.generate();
        this.applicationId = applicationId;
        this.type = type;
        this.status = RequirementStatus.PENDING;
    }

    public static EvidenceRequirement pending(UUID applicationId, RequirementType type) {
        return new EvidenceRequirement(applicationId, type);
    }

    /** Its check answered. The answer itself lives on the check's raw response. */
    public void receive(EvidenceSource source) {
        status = RequirementStatus.RECEIVED;
        switch (source) {
            case EvidenceSource.Vendor vendor -> sourceVendorCheckId = vendor.id();
            case EvidenceSource.Upload upload -> sourceDocumentId = upload.id();
        }
    }

    /**
     * The applicant must upload something; a re-upload will start a fresh check.
     * <p>
     * {@code sourceDocumentId} is deliberately <em>kept</em>. It records the document this requirement last
     * acted on, and without it the orchestrator cannot tell "a new document arrived" from "the rejected one is
     * still the newest" — and would queue a second paid check for evidence the vendor has already refused.
     */
    public void needEvidence() {
        status = RequirementStatus.NEEDS_EVIDENCE;
        sourceVendorCheckId = null;
    }

    /** Back to waiting, because new evidence arrived. */
    public void retry() {
        status = RequirementStatus.PENDING;
    }

    /**
     * Waiting on a check for this document. The document id is kept while PENDING so a redelivered event does
     * not queue a second paid check for evidence already in flight.
     */
    public void awaitCheckFor(UUID documentId) {
        status = RequirementStatus.PENDING;
        sourceDocumentId = documentId;
        sourceVendorCheckId = null;
    }

    /** No answer was obtained. Visible in the response rather than silently absent. */
    public void markUnavailable() {
        status = RequirementStatus.UNAVAILABLE;
    }

    /**
     * What <em>answered</em> this requirement, which only exists once it is RECEIVED. While it is pending or
     * needing evidence, {@code sourceDocumentId} is a bookmark rather than an answer, so it is not reported
     * here.
     */
    public EvidenceSource source() {
        if (status != RequirementStatus.RECEIVED) {
            return null;
        }
        if (sourceVendorCheckId != null) {
            return new EvidenceSource.Vendor(sourceVendorCheckId);
        }
        if (sourceDocumentId != null) {
            return new EvidenceSource.Upload(sourceDocumentId);
        }
        return null;
    }

    public boolean isSettled() {
        return status.isSettled();
    }
}
