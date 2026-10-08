package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.ApplicationStatus;
import com.mettyoung.creditcardapplication.shared.DomainException;

import java.util.Map;

/** FR8.3: a reviewer can only decide an application the system referred. */
class NotReferredException extends DomainException {

    private final ApplicationStatus currentStatus;

    public NotReferredException(ApplicationStatus currentStatus) {
        super(Category.CONFLICTING_STATE, "Only a referred application can be reviewed.");
        this.currentStatus = currentStatus;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("currentStatus", currentStatus);
    }
}
