package com.mettyoung.creditcardapplication.document.internal;

import com.mettyoung.creditcardapplication.document.DocumentKind;
import com.mettyoung.creditcardapplication.document.DocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface DocumentRepository extends JpaRepository<Document, UUID> {

    // Ownership is part of the query, so absent and not-yours are one outcome.
    Optional<Document> findByIdAndApplicationIdAndUserId(UUID id, UUID applicationId, String userId);

    List<Document> findByApplicationIdOrderByIdDesc(UUID applicationId);

    List<Document> findByApplicationIdAndKindAndStatus(UUID applicationId, DocumentKind kind, DocumentStatus status);

    boolean existsByApplicationIdAndKindAndStatus(UUID applicationId, DocumentKind kind, DocumentStatus status);

    List<Document> findByStatusAndCreatedAtBefore(DocumentStatus status, Instant cutoff);
}
