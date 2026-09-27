package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.internal.Application;
import com.mettyoung.creditcardapplication.shared.DomainException;

/** Carries nothing: a not-found response must not hint at what exists. */
class ApplicationNotFoundException extends DomainException {

    public ApplicationNotFoundException() {
        super(Category.NOT_FOUND, "Application not found.");
    }
}
