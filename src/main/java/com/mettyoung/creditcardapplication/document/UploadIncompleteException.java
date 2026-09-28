package com.mettyoung.creditcardapplication.document;

import com.mettyoung.creditcardapplication.shared.DomainException;

import java.util.Map;

/**
 * {@code /complete} was called but no object is there. A conflict, not a not-found: the document row exists
 * and the caller can fix this by actually uploading the bytes.
 */
public class UploadIncompleteException extends DomainException {

    private final DocumentStatus currentStatus;

    public UploadIncompleteException(DocumentStatus currentStatus) {
        super(Category.CONFLICTING_STATE, "No uploaded object was found for this document.");
        this.currentStatus = currentStatus;
    }

    @Override
    public Map<String, Object> details() {
        return Map.of("currentStatus", currentStatus);
    }
}
