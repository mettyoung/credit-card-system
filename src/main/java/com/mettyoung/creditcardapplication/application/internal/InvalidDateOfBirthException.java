package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.internal.DateOfBirth;
import com.mettyoung.creditcardapplication.shared.DomainException;

import java.util.Map;

/** The message describes the rule and never echoes the rejected date, which is PII. */
class InvalidDateOfBirthException extends DomainException {

    public InvalidDateOfBirthException(String reason) {
        super(Category.INVALID_VALUE, reason);
    }

    @Override
    public Map<String, Object> details() {
        return Map.of(DateOfBirth.FIELD, getMessage());
    }
}
