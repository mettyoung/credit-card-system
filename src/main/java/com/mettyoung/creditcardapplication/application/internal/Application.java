package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.UpdateDraftCommand;
import com.mettyoung.creditcardapplication.application.ApplicationStatus;
import com.mettyoung.creditcardapplication.application.CardProduct;
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

import java.util.Objects;
import java.util.UUID;

// No setters on purpose: state changes only through the domain methods below.
@Getter
@Entity
@Table(name = "application")
class Application {

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
}
