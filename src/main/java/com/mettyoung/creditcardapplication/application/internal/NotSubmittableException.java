package com.mettyoung.creditcardapplication.application.internal;

import java.util.List;
import java.util.Map;

import com.mettyoung.creditcardapplication.shared.DomainException;

/**
 * Submit was refused because something the applicant must still provide is absent.
 * <p>
 * {@code CONFLICTING_STATE}, not {@code INVALID_VALUE}: submit carries no body, so there is no request value
 * to correct — what has to change is the application. That also gives the response a
 * {@code /problems/not-submittable} type and {@code missing} as a top-level member, rather than flattening it
 * into the {@code errors[]} array that a malformed request uses.
 * <p>
 * {@code missing} holds field and evidence names, never their values.
 */
class NotSubmittableException extends DomainException {

    private final List<String> missing;

    public NotSubmittableException(List<String> missing) {
        super(Category.CONFLICTING_STATE, "The application is not complete enough to submit.");
        this.missing = List.copyOf(missing);
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("missing", missing);
    }

    public List<String> missing() {
        return missing;
    }
}
