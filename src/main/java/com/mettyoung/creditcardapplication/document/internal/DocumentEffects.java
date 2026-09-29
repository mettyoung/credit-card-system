package com.mettyoung.creditcardapplication.document.internal;

import com.mettyoung.creditcardapplication.audit.AuditEntry;
import com.mettyoung.creditcardapplication.audit.AuditEventType;
import com.mettyoung.creditcardapplication.audit.Audits;
import com.mettyoung.creditcardapplication.document.DocumentStatus;
import com.mettyoung.creditcardapplication.shared.outbox.DomainEvent.DocumentUploaded;
import com.mettyoung.creditcardapplication.shared.outbox.OutboxWriter;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The consequences that commit <em>with</em> a document's facts: the audit rows, and the outbox row that lets
 * the workflow react. What happens <em>next</em> with that outbox row is the orchestrator's, in a later
 * transaction the relay opens.
 * <p>
 * Reached through {@code @DomainEvents}: the aggregate records what it did, Spring Data publishes that when
 * the repository saves, and this listens — so {@code DocumentService} no longer has to remember to write an
 * audit row beside every state change.
 * <p>
 * Plain {@link EventListener}s, not {@code @TransactionalEventListener}: publication happens inside
 * {@code save}, so these run on the same thread inside the caller's transaction. {@code Audits.record} is
 * {@code MANDATORY} and would refuse outside one, and an outbox row written after the commit it describes
 * could be lost by a crash in between.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class DocumentEffects {

    private final DocumentRepository documents;
    private final Audits auditLog;
    private final OutboxWriter outbox;

    @EventListener
    void on(DomainEvent.UploadRequested event) {
        Document document = documents.findById(event.documentId()).orElseThrow();

        auditLog.record(AuditEntry.byApplicant(document.getApplicationId(), AuditEventType.UPLOAD_REQUESTED,
                document.getUserId(),
                Map.of(
                        "documentId", document.getId(),
                        "kind", document.getKind(),
                        "sizeBytes", document.getSizeBytes())));
    }

    @EventListener
    void on(DomainEvent.Settled event) {
        Document document = documents.findById(event.documentId()).orElseThrow();

        auditLog.record(AuditEntry.byApplicant(document.getApplicationId(), AuditEventType.DOCUMENT_VERIFIED,
                document.getUserId(),
                Map.of(
                        "documentId", document.getId(),
                        "kind", document.getKind(),
                        "status", document.getStatus(),
                        // The empty string rather than null: a payload key that sometimes vanishes is harder
                        // to read back than one that is sometimes blank.
                        "reason", event.reason() == null ? "" : event.reason())));

        // Only an accepted document can move a requirement forward, so only that is worth an event.
        if (document.getStatus() == DocumentStatus.UPLOADED) {
            outbox.write(new DocumentUploaded(document.getApplicationId(), document.getId(),
                    document.getKind().name()));
        }
    }
}
