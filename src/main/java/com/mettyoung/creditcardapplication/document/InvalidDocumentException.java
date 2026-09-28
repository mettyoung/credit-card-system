package com.mettyoung.creditcardapplication.document;

import com.mettyoung.creditcardapplication.shared.DomainException;

import java.util.Map;

/**
 * A declared upload attribute broke a rule. Carries the field so the problem response names it, the same
 * way the declared-data exceptions do. Never echoes the rejected value.
 */
public class InvalidDocumentException extends DomainException {

    private final String field;

    public InvalidDocumentException(String field, String reason) {
        super(Category.INVALID_VALUE, reason);
        this.field = field;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of(field, getMessage());
    }
}
