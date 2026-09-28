package com.mettyoung.creditcardapplication.document;

import com.mettyoung.creditcardapplication.shared.DomainException;

/** Absent, or not this applicant's. One outcome, so a response can't confirm what exists. */
public class DocumentNotFoundException extends DomainException {

    public DocumentNotFoundException() {
        super(Category.NOT_FOUND, "Document not found.");
    }
}
