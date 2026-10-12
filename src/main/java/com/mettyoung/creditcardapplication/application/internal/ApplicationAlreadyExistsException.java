package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.shared.DomainException;

import java.util.Map;
import java.util.UUID;

/**
 * I4 / FR1.5: the user already has an application for this card product that is past DRAFT and not declined or
 * expired - still being checked, waiting for a reviewer, or approved. Only a decline or an expiry frees the slot.
 * <p>
 * Names the application, which is the caller's own, but not its status: the message is the same whether it is
 * being checked, referred or approved.
 */
class ApplicationAlreadyExistsException extends DomainException {

    private final UUID applicationId;

    public ApplicationAlreadyExistsException(UUID applicationId) {
        super(Category.CONFLICTING_STATE, "An application already exists for this card product.");
        this.applicationId = applicationId;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("applicationId", applicationId);
    }
}
