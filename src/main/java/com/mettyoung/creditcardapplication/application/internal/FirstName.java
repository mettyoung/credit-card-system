package com.mettyoung.creditcardapplication.application.internal;


/**
 * Validated in the constructor, so every instance is valid. Surrounding whitespace is stripped.
 * <p>
 * A distinct type from {@link LastName} on purpose: the two are the same shape but not interchangeable, and
 * the compiler refuses to swap them at a call site.
 *
 * @throws InvalidNameException if the value breaks {@link NameRules}
 */
record FirstName(String value) {

    public static final int MAX_LENGTH = NameRules.MAX_LENGTH;

    static final String FIELD = "firstName";

    public FirstName {
        value = NameRules.validate(value, FIELD);
    }
}
