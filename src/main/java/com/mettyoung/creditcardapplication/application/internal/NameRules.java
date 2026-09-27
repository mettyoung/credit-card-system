package com.mettyoung.creditcardapplication.application.internal;


/**
 * The rules shared by {@link FirstName} and {@link LastName}. Package-private: the value objects are the
 * only way to apply them, so no caller can validate a name without producing one.
 * <p>
 * The limit is per part, not for the two together — 70 each rather than 100 shared, so a long surname can't
 * be rejected because the given name was long.
 */
final class NameRules {

    static final int MAX_LENGTH = 70;

    private NameRules() {
    }

    /**
     * @return the stripped value
     * @throws InvalidNameException if {@code raw} is absent, blank, too long, or holds control characters
     */
    static String validate(String raw, String field) {
        if (raw == null) {
            throw new InvalidNameException(field, field + " is required.");
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            throw new InvalidNameException(field, field + " must not be blank.");
        }
        // Count code points, not UTF-16 chars, to match Postgres varchar(70) semantics.
        if (value.codePointCount(0, value.length()) > MAX_LENGTH) {
            throw new InvalidNameException(field, field + " must be at most " + MAX_LENGTH + " characters.");
        }
        if (value.codePoints().anyMatch(cp -> Character.getType(cp) == Character.CONTROL)) {
            throw new InvalidNameException(field, field + " must not contain control characters.");
        }
        return value;
    }
}
