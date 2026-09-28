package com.mettyoung.creditcardapplication.document;

import com.mettyoung.creditcardapplication.shared.DomainException;

import java.util.Map;

/**
 * The document has already reached a verdict, so it cannot be given another one. Verdicts are one-way:
 * replaying {@code /complete} re-reads the object and reaches the same answer, but nothing may overturn it.
 * <p>
 * Distinct from {@link UploadIncompleteException}, which means the opposite — no object arrived at all.
 * Sharing one exception for both would answer "no uploaded object was found" while reporting a
 * {@code currentStatus} of {@code UPLOADED}.
 */
public class DocumentAlreadySettledException extends DomainException {

    private final DocumentStatus currentStatus;

    public DocumentAlreadySettledException(DocumentStatus currentStatus) {
        super(Category.CONFLICTING_STATE, "This document has already been verified.");
        this.currentStatus = currentStatus;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("currentStatus", currentStatus);
    }

    public DocumentStatus currentStatus() {
        return currentStatus;
    }
}
