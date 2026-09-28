package com.mettyoung.creditcardapplication.document;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

/**
 * What a document is for. {@code ID} answers the identity requirement; {@code PAYSLIP} will answer income
 * in FR6 — accepted now so the model isn't reshaped later, consumed by nothing yet.
 */
public enum DocumentKind {
    ID,
    PAYSLIP;

    static final String FIELD = "kind";

    /**
     * Binds the request field. Jackson would otherwise reject an unknown value with its own message and a
     * path, and the caller would get a different shape of error than every other invalid field.
     *
     * @throws InvalidDocumentException if absent or not one of the constants
     */
    @JsonCreator
    public static DocumentKind of(String raw) {
        if (raw == null) {
            throw new InvalidDocumentException(FIELD, FIELD + " is required.");
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidDocumentException(FIELD, FIELD + " must be one of ID, PAYSLIP.");
        }
    }
}
