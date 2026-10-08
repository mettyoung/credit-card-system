package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.audit.Actor;
import com.mettyoung.creditcardapplication.audit.AuditEntry;
import com.mettyoung.creditcardapplication.audit.AuditEventType;
import com.mettyoung.creditcardapplication.audit.Audits;
import com.mettyoung.creditcardapplication.application.ApplicationResponse;
import com.mettyoung.creditcardapplication.application.ApplicationStatus;
import com.mettyoung.creditcardapplication.application.Applications;
import com.mettyoung.creditcardapplication.application.CardProduct;
import com.mettyoung.creditcardapplication.application.CreateDraftCommand;
import com.mettyoung.creditcardapplication.application.UpdateDraftCommand;
import com.mettyoung.creditcardapplication.document.DocumentKind;
import com.mettyoung.creditcardapplication.document.Documents;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Errors leave as domain exceptions, which the controller advice turns into problem responses.
 */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class ApplicationService implements Applications {

    static final String ONE_DRAFT_CONSTRAINT = "ux_application_one_draft";

    private final ApplicationRepository repository;
    private final EvidenceRequirementRepository requirements;
    private final Documents documents;
    private final ApplicationMapper mapper;
    private final TransactionTemplate transaction;
    private final Audits audits;

    /**
     * Not transactional on purpose. The insert runs in the repository's own transaction; after a
     * unique violation that transaction is aborted, so the lookup of the existing draft needs a new one.
     * No check-then-insert: the partial unique index is the only reliable arbiter under concurrency.
     *
     * @throws DraftAlreadyExistsException if the user already has a draft for this product
     */
    @Override
    public ApplicationResponse createDraft(String userId, CreateDraftCommand command) {
        CardProduct cardProductCode = command.cardProductCode();
        try {
            return respond(repository.saveAndFlush(Application.createDraft(userId, cardProductCode)));
        } catch (DataIntegrityViolationException e) {
            if (!violatesConstraint(e, ONE_DRAFT_CONSTRAINT)) {
                throw e;
            }
            UUID existingId = repository
                    .findByUserIdAndCardProductCodeAndStatus(userId, cardProductCode, ApplicationStatus.DRAFT)
                    .map(Application::getId)
                    .orElseThrow(() -> new IllegalStateException("Draft constraint violated but no draft found", e));
            throw new DraftAlreadyExistsException(existingId);
        }
    }

    /**
     * The transaction is opened and closed inside this method (not via @Transactional) so a lost
     * optimistic-lock race can be translated after rollback. With @Transactional, the failing repository
     * call would mark the outer transaction rollback-only and the proxy would throw
     * UnexpectedRollbackException at commit, after we had already translated the failure.
     *
     * @throws ApplicationNotFoundException if no such application belongs to this user
     * @throws VersionMismatchException     if the client's version is stale or a concurrent writer won
     */
    @Override
    public ApplicationResponse updateDraft(String userId, UUID id, UpdateDraftCommand command) {
        long expectedVersion = command.version();
        try {
            return respond(transaction.execute(status -> updateInTransaction(userId, id, command)));
        } catch (OptimisticLockingFailureException e) {
            // A concurrent writer committed between our SELECT and UPDATE.
            long currentVersion = repository.findByIdAndUserId(id, userId)
                    .map(Application::getVersion)
                    .orElse(expectedVersion);
            throw new VersionMismatchException(currentVersion);
        }
    }

    /**
     * @throws ApplicationNotFoundException if no such application belongs to this user
     */
    @Override
    public ApplicationResponse get(String userId, UUID id) {
        return respond(load(userId, id));
    }

    /**
     * Records intake and nothing else: the transition, its audit row and one outbox event, in a single
     * transaction. Queueing the checks is the orchestrator's job, reached through that event - so a crash
     * between the two costs a moment, not a lost application.
     *
     * @throws ApplicationNotFoundException if no such application belongs to this user
     * @throws NotEditableException         if it is no longer a draft
     * @throws NotSubmittableException      if the declared data or the ID document is incomplete
     */
    @Override
    public ApplicationResponse submit(String userId, UUID id) {
        return transaction.execute(status -> {
            Application application = load(userId, id);
            application.submit(documents.hasAccepted(id, DocumentKind.ID));
            // This call is load-bearing, which it was not before: Spring Data publishes @DomainEvents from
            // the repository save, so without it the transition would still commit - the entity is managed
            // and Hibernate flushes it at commit - with no audit row and no outbox row. Silently.
            //
            // save rather than saveAndFlush because nothing here needs the early flush: no catch depends on
            // a conflict surfacing inside this method, and submitting removes the row from the one-draft
            // partial index, so there is no uniqueness violation to provoke.
            repository.save(application);
            return respond(application);
        });
    }

    /** FR8.2: every referred application, for a reviewer. Not by owner: the reviewer is not the applicant. */
    List<ReviewQueueItem> referred() {
        return repository.findByStatusOrderByIdAsc(ApplicationStatus.REFERRED).stream()
                .map(application -> new ReviewQueueItem(application.getId(), application.getCardProductCode(),
                        application.getDecisionReason(), application.getVersion()))
                .toList();
    }

    /**
     * FR8.3: a reviewer approves or declines a referred application. Its own transaction, for the same reason
     * as {@link #updateDraft}: a lost optimistic-lock race is translated after the rollback.
     *
     * @throws ApplicationNotFoundException  if there is no such application
     * @throws VersionMismatchException      if the reviewer's copy is stale, or a concurrent decision won
     * @throws NotReferredException          if it is not waiting for a reviewer
     * @throws InvalidDecisionReasonException if a decline names no reviewer's reason
     */
    ApplicationResponse review(String reviewerId, UUID id, ReviewRequest request) {
        try {
            return respond(transaction.execute(status -> reviewInTransaction(reviewerId, id, request)));
        } catch (OptimisticLockingFailureException e) {
            long currentVersion = repository.findById(id).map(Application::getVersion).orElse(request.version());
            throw new VersionMismatchException(currentVersion);
        }
    }

    private Application reviewInTransaction(String reviewerId, UUID id, ReviewRequest request) {
        Application application = repository.findById(id).orElseThrow(ApplicationNotFoundException::new);
        if (!application.getVersion().equals(request.version())) {
            throw new VersionMismatchException(application.getVersion());
        }
        switch (request.outcome()) {
            case APPROVED -> application.approveOnReview();
            case DECLINED -> application.declineOnReview(request.reason());
        }
        repository.saveAndFlush(application);

        Map<String, Object> payload = new HashMap<>();
        payload.put("outcome", application.getStatus());
        payload.put("by", Actor.REVIEWER);
        if (application.getStatus() == ApplicationStatus.DECLINED) {
            payload.put("reason", application.getDecisionReason());
        }
        audits.record(AuditEntry.byReviewer(id, AuditEventType.DECISION_MADE, reviewerId, payload));
        return application;
    }

    private ApplicationResponse respond(Application application) {
        return mapper.toResponse(application,
                mapper.toRequirements(requirements.findByApplicationId(application.getId())));
    }

    private Application load(String userId, UUID id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(ApplicationNotFoundException::new);
    }

    /**
     * Ownership guard for the upload endpoints: a document can only be hung on an application the caller owns.
     * Package-private, because the only caller is this module's own document controller - it is not part of
     * what {@link Applications} promises anyone else.
     * <p>
     * The status rule lives here rather than in the document module: a document knows nothing about which
     * statuses may still gather evidence.
     *
     * @throws ApplicationNotFoundException if no such application belongs to this user
     * @throws NotEditableException         if it is past gathering evidence
     */
    void requireUploadable(String userId, UUID id) {
        Application application = load(userId, id);
        if (!application.acceptsUploads()) {
            throw new NotEditableException(application.getStatus());
        }
    }

    @Override
    public List<ApplicationResponse.Summary> list(String userId, ApplicationStatus status) {
        // Straight to the projection: a list never needs the aggregate hydrated, and the optional filter is
        // a condition rather than a choice between two finders.
        return repository.listFor(userId, status);
    }

    private Application updateInTransaction(String userId, UUID id, UpdateDraftCommand command) {
        Application application = load(userId, id);
        // Stale copy: the client last saw an older version than what is stored.
        if (application.getVersion() != command.version()) {
            throw new VersionMismatchException(application.getVersion());
        }
        application.on(command);
        // Flush now so a concurrent write surfaces here as an optimistic-lock failure, not at commit.
        // Unchanged declared data is not dirty, so Hibernate issues no UPDATE and the version stays put.
        repository.saveAndFlush(application);
        return application;
    }

    private static boolean violatesConstraint(Throwable e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
