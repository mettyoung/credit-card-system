package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.shared.DomainException;

import java.util.Map;
import java.util.UUID;

/**
 * I4 / FR1.5: the user already has a draft for this card product. The usual cause is a retried create, so the
 * draft is named and the client carries on with it.
 */
class DraftAlreadyExistsException extends DomainException {

    private final UUID applicationId;

    public DraftAlreadyExistsException(UUID applicationId) {
        super(Category.CONFLICTING_STATE, "A draft already exists for this card product.");
        this.applicationId = applicationId;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("applicationId", applicationId);
    }

    public UUID applicationId() {
        return applicationId;
    }
}
