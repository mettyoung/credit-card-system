package com.mettyoung.creditcardapplication.application.internal;


/**
 * Validated in the constructor, so every instance is valid. Surrounding whitespace is stripped.
 *
 * @throws InvalidNameException if the value breaks {@link NameRules}
 * @see FirstName for why this is a separate type
 */
record LastName(String value) {

    public static final int MAX_LENGTH = NameRules.MAX_LENGTH;

    static final String FIELD = "lastName";

    public LastName {
        value = NameRules.validate(value, FIELD);
    }
}
