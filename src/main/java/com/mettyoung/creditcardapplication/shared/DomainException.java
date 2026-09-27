package com.mettyoung.creditcardapplication.shared;

import java.util.Map;

/**
 * A domain rule refused an operation. Every domain exception extends this directly and names its
 * {@link Category}, which is what the web layer maps to a status — nothing here knows about HTTP.
 * <p>
 * The advice switches over the category, so adding a constant fails the build until it is mapped, while a new
 * exception of an existing category needs no web change.
 */
public abstract class DomainException extends RuntimeException {

    public final Category category;

    /**
     * What kind of refusal this is, which decides the status and the response shape.
     */
    public enum Category {
        /** A value object rejected its input; the caller can fix it by editing the request. */
        INVALID_VALUE,
        /** The aggregate's state forbids the operation; editing the request won't help. */
        CONFLICTING_STATE,
        /** Absent, or not the caller's. The two are one outcome, so a response can't confirm what exists. */
        NOT_FOUND
    }

    protected DomainException(Category category, String detail) {
        super(detail);
        this.category = category;
    }

    /**
     * The problem type. Derived from the class name without its {@code Exception} suffix
     * ({@code DraftAlreadyExistsException} → {@code draft-already-exists}),
     * except where the category fixes it: every not-found looks alike, and an invalid value is a malformed
     * request like any other.
     */
    public String code() {
        return switch (category) {
            case NOT_FOUND -> "not-found";
            case INVALID_VALUE -> "invalid-request";
            case CONFLICTING_STATE -> getClass().getSimpleName()
                    .replaceAll("Exception$", "")
                    .replaceAll("(?<=[a-z0-9])(?=[A-Z])", "-")
                    .toLowerCase();
        };
    }

    /**
     * Facts about the refusal, copied onto the problem response, so never PII. Keys must avoid the RFC 9457
     * members ({@code type}, {@code title}, {@code status}, {@code detail}, {@code instance}), which they
     * would shadow.
     */
    public Map<String, Object> details() {
        return Map.of();
    }
}
