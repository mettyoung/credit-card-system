package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.ApplicationStatus;
import com.mettyoung.creditcardapplication.shared.DomainException;

import java.util.Map;

/** Past {@code DRAFT}: declared data is immutable once the application is submitted. */
class NotEditableException extends DomainException {

    private final ApplicationStatus currentStatus;

    public NotEditableException(ApplicationStatus currentStatus) {
        super(Category.CONFLICTING_STATE, "The application can no longer be edited.");
        this.currentStatus = currentStatus;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("currentStatus", currentStatus);
    }

    public ApplicationStatus currentStatus() {
        return currentStatus;
    }
}
