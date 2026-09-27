package com.mettyoung.creditcardapplication.application.internal;

import java.time.LocalDate;

/**
 * Validated in the constructor, so every instance is valid.
 * <p>
 * Both bounds are chosen so a row that was valid when written stays valid when read. "In the past" is
 * monotonic — a past date never becomes a future one — and the floor is an absolute date rather than
 * "within the last 120 years", which would make an applicant's own row fail to load as they aged.
 * <p>
 * This is validity, not eligibility: a minimum age is a policy rule and belongs to the ruleset, which can
 * change it without a migration.
 *
 * @throws InvalidDateOfBirthException if absent, not in the past, or before {@link #FLOOR}
 */
record DateOfBirth(LocalDate value) {

    /** Nobody applying for a card was born earlier, and an absolute floor can live in a CHECK constraint. */
    public static final LocalDate FLOOR = LocalDate.of(1900, 1, 1);

    static final String FIELD = "dateOfBirth";

    public DateOfBirth {
        if (value == null) {
            throw new InvalidDateOfBirthException(FIELD + " is required.");
        }
        if (!value.isBefore(LocalDate.now())) {
            throw new InvalidDateOfBirthException(FIELD + " must be in the past.");
        }
        if (value.isBefore(FLOOR)) {
            throw new InvalidDateOfBirthException(FIELD + " must not be before " + FLOOR + ".");
        }
    }
}
