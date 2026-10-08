package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.UpdateDraftCommand;
import com.mettyoung.creditcardapplication.application.ApplicationStatus;
import com.mettyoung.creditcardapplication.application.CardProduct;
import com.mettyoung.creditcardapplication.application.DecisionReason;
import com.mettyoung.creditcardapplication.shared.UuidV7;
import com.mettyoung.creditcardapplication.shared.outbox.DomainEvent;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

// No setters on purpose: state changes only through the domain methods below.
@Getter
@Entity
@Table(name = "application")
/**
 * Extends {@link AbstractAggregateRoot} for the event plumbing only: {@code registerEvent} records what a
 * call did, Spring Data publishes it when the repository saves, and clears it afterwards so a second save
 * cannot publish it twice.
 */
class Application extends AbstractAggregateRoot<Application> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "card_product_code", nullable = false, updatable = false)
    private CardProduct cardProductCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ApplicationStatus status;

    // Declared data: all null until the applicant fills the form in, then all set together.
    @Convert(converter = FirstNameConverter.class)
    @Column(name = "first_name", length = FirstName.MAX_LENGTH)
    private FirstName firstName;

    @Convert(converter = LastNameConverter.class)
    @Column(name = "last_name", length = LastName.MAX_LENGTH)
    private LastName lastName;

    @Convert(converter = DateOfBirthConverter.class)
    @Column(name = "date_of_birth")
    private DateOfBirth dateOfBirth;

    @Convert(converter = CountryConverter.class)
    @Column(name = "country", length = Country.LENGTH)
    private Country country;

    /**
     * FR9: when the application entered its current status - the one timestamp both deadlines read ("in
     * NEEDS_INFO since", "referred since"). Set by every transition; null while it is a draft.
     */
    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    /** Why it was referred, or why a reviewer declined it (FR8). Never shown to the applicant. */
    @Enumerated(EnumType.STRING)
    @Column(name = "decision_reason")
    private DecisionReason decisionReason;

    // Wrapper type on purpose: Spring Data treats a null version as "new" and calls persist.
    // With a primitive it would fall back to the pre-assigned id and issue a merge instead.
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Application() {
        // for JPA
    }

    private Application(UUID id, String userId, CardProduct cardProductCode) {
        this.id = id;
        this.userId = userId;
        this.cardProductCode = cardProductCode;
        this.status = ApplicationStatus.DRAFT;
    }

    public static Application createDraft(String userId, CardProduct cardProductCode) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(cardProductCode, "cardProductCode");
        return new Application(UuidV7.generate(), userId, cardProductCode);
    }

    /**
     * The four declared fields are saved together: the client sends the whole form, so a partial apply would
     * leave the aggregate in a state no request asked for.
     * <p>
     * Every value object is built before anything is assigned, so a rejection on the third field leaves the
     * first two as they were. The first invalid field wins — the client fixes one at a time, which is the
     * cost of one {@code DomainException} per refusal.
     *
     * Named for the command it applies rather than for the fields it sets: the aggregate answers a request
     * the applicant made, and the next command gets its own method instead of another argument here.
     * {@code version} is not the aggregate's business - the service checks it before calling.
     *
     * @throws NotEditableException          if the application is no longer a draft
     * @throws InvalidNameException          if either name part is invalid
     * @throws InvalidDateOfBirthException   if the date of birth is absent or out of range
     * @throws InvalidCountryException       if the country is not an ISO 3166-1 alpha-2 code
     */
    public void on(UpdateDraftCommand command) {
        if (status != ApplicationStatus.DRAFT) {
            throw new NotEditableException(status);
        }
        FirstName validFirstName = new FirstName(command.firstName());
        LastName validLastName = new LastName(command.lastName());
        DateOfBirth validDateOfBirth = new DateOfBirth(command.dateOfBirth());
        Country validCountry = new Country(command.country());

        firstName = validFirstName;
        lastName = validLastName;
        dateOfBirth = validDateOfBirth;
        country = validCountry;
    }

    /**
     * Records intake and nothing else. Creating the requirements and the vendor checks is the orchestrator's
     * job, reached through the outbox — which is what makes a crash between the two harmless.
     *
     * @param hasIdDocument whether an accepted {@code ID} document exists; the document module owns that fact
     * @param at            when it happened, from the caller's clock
     * @throws NotEditableException      if the application is no longer a draft
     * @throws NotSubmittableException   if declared data is incomplete or no ID document has been accepted
     */
    public void submit(boolean hasIdDocument, Instant at) {
        if (status != ApplicationStatus.DRAFT) {
            throw new NotEditableException(status);
        }
        List<String> missing = new ArrayList<>();
        if (firstName == null) {
            missing.add("firstName");
        }
        if (lastName == null) {
            missing.add("lastName");
        }
        if (dateOfBirth == null) {
            missing.add("dateOfBirth");
        }
        if (country == null) {
            missing.add("country");
        }
        if (!hasIdDocument) {
            missing.add("ID");
        }
        if (!missing.isEmpty()) {
            throw new NotSubmittableException(missing);
        }
        moveTo(ApplicationStatus.SUBMITTED, at);
        // The aggregate says what happened; what that costs - an audit row, an outbox row - is decided by a
        // listener, in the transaction this save runs in.
        registerEvent(new DomainEvent.ApplicationSubmitted(id));
    }

    /**
     * @throws NotEditableException if intake has not been recorded
     */
    public void startVerifying(Instant at) {
        requireStatus(ApplicationStatus.SUBMITTED);
        moveTo(ApplicationStatus.VERIFYING, at);
    }

    /**
     * @throws NotEditableException if no check is outstanding
     */
    public void requestInfo(Instant at) {
        requireStatus(ApplicationStatus.VERIFYING);
        moveTo(ApplicationStatus.NEEDS_INFO, at);
    }

    /**
     * @throws NotEditableException if the application is not waiting on the applicant
     */
    public void resumeVerifying(Instant at) {
        requireStatus(ApplicationStatus.NEEDS_INFO);
        moveTo(ApplicationStatus.VERIFYING, at);
    }

    /**
     * @throws NotEditableException if no check is outstanding
     */
    public void completeChecks(Instant at) {
        requireStatus(ApplicationStatus.VERIFYING);
        moveTo(ApplicationStatus.CHECKS_COMPLETE, at);
    }

    /**
     * FR8.1: the system's decision on a clean result.
     *
     * @throws NotEditableException if the checks have not completed, or it is already decided
     */
    public void approve(Instant at) {
        requireStatus(ApplicationStatus.CHECKS_COMPLETE);
        moveTo(ApplicationStatus.APPROVED, at);
    }

    /**
     * FR8.1: anything that is not a clean result goes to a person. The system never declines.
     *
     * @throws NotEditableException if the checks have not completed, or it is already decided
     */
    public void refer(DecisionReason reason, Instant at) {
        requireStatus(ApplicationStatus.CHECKS_COMPLETE);
        if (reason.isReviewerReason()) {
            throw new IllegalArgumentException("A referral needs a system reason, not " + reason);
        }
        moveTo(ApplicationStatus.REFERRED, at);
        decisionReason = reason;
    }

    /**
     * FR8.3: a reviewer approves a referred application. The referral reason stays, as the record of why it was
     * looked at.
     *
     * @throws NotReferredException if it is not waiting for a reviewer
     */
    public void approveOnReview(Instant at) {
        requireReferred();
        moveTo(ApplicationStatus.APPROVED, at);
    }

    /**
     * FR8.3: only a reviewer declines, and only with a reason of their own.
     *
     * @throws NotReferredException          if it is not waiting for a reviewer
     * @throws InvalidDecisionReasonException if the reason is missing or is one only the system sets
     */
    public void declineOnReview(DecisionReason reason, Instant at) {
        requireReferred();
        if (reason == null || !reason.isReviewerReason()) {
            throw new InvalidDecisionReasonException();
        }
        moveTo(ApplicationStatus.DECLINED, at);
        decisionReason = reason;
    }

    /**
     * FR9.1: the applicant never sent what was asked for. Terminal, and not a decline - nobody judged them.
     *
     * @throws NotEditableException if it is not waiting on the applicant
     */
    public void expire(Instant at) {
        requireStatus(ApplicationStatus.NEEDS_INFO);
        moveTo(ApplicationStatus.EXPIRED, at);
    }

    /** FR9.1: past its deadline in NEEDS_INFO, measured from when it entered it. */
    public boolean isPastNeedsInfoDeadline(Instant now, Duration deadline) {
        return status == ApplicationStatus.NEEDS_INFO && statusChangedAt != null
                && !now.isBefore(statusChangedAt.plus(deadline));
    }

    /** FR9.3: decided or expired; nothing may move it any more. */
    public boolean isTerminal() {
        return status == ApplicationStatus.APPROVED || status == ApplicationStatus.DECLINED
                || status == ApplicationStatus.EXPIRED;
    }

    private void moveTo(ApplicationStatus next, Instant at) {
        status = next;
        statusChangedAt = at;
    }

    private void requireReferred() {
        if (status != ApplicationStatus.REFERRED) {
            throw new NotReferredException(status);
        }
    }

    /** I11 in reverse: evidence is only accepted while the application is still gathering it. */
    public boolean acceptsUploads() {
        return status.acceptsUploads();
    }

    private void requireStatus(ApplicationStatus expected) {
        if (status != expected) {
            throw new NotEditableException(status);
        }
    }

}
