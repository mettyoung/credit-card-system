package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.shared.DomainException;

import java.util.Map;

/**
 * One class for both name parts, carrying which one was rejected. The message describes the rule and never
 * echoes the rejected value, which is PII.
 * <p>
 * The category fixes the problem type to {@code invalid-request}, so the class name never reaches the client
 * and splitting this into one exception per field would buy nothing.
 */
class InvalidNameException extends DomainException {

    private final String field;

    public InvalidNameException(String field, String reason) {
        super(Category.INVALID_VALUE, reason);
        this.field = field;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of(field, getMessage());
    }

    public String field() {
        return field;
    }
}
