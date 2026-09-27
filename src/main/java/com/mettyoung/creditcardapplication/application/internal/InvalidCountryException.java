package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.internal.Country;
import com.mettyoung.creditcardapplication.shared.DomainException;

import java.util.Map;

/** The message describes the rule and never echoes the rejected code. */
class InvalidCountryException extends DomainException {

    public InvalidCountryException(String reason) {
        super(Category.INVALID_VALUE, reason);
    }

    @Override
    public Map<String, Object> details() {
        return Map.of(Country.FIELD, getMessage());
    }
}
