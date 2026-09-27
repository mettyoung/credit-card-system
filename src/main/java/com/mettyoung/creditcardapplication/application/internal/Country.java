package com.mettyoung.creditcardapplication.application.internal;

import java.util.Locale;
import java.util.Set;

/**
 * An ISO 3166-1 alpha-2 country code, upper-cased. Validated in the constructor, so every instance is valid.
 * <p>
 * Membership is checked against the JDK's list rather than a hand-kept one, which has a consequence worth
 * naming: ISO does withdraw codes, so a row written under an older JDK can fail to load under a newer one.
 * That is the converter contract working as intended — a row that no longer satisfies the rules should stop
 * the read and be fixed by a migration, not flow on as a valid country.
 *
 * @throws InvalidCountryException if absent or not a current ISO 3166-1 alpha-2 code
 */
record Country(String code) {

    public static final int LENGTH = 2;

    static final String FIELD = "country";

    private static final Set<String> ISO_CODES = Set.of(Locale.getISOCountries());

    public Country {
        if (code == null) {
            throw new InvalidCountryException(FIELD + " is required.");
        }
        code = code.strip().toUpperCase(Locale.ROOT);
        if (!ISO_CODES.contains(code)) {
            throw new InvalidCountryException(FIELD + " must be an ISO 3166-1 alpha-2 code.");
        }
    }
}
