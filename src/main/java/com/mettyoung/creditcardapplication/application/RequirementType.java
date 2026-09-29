package com.mettyoung.creditcardapplication.application;

import com.mettyoung.creditcardapplication.document.DocumentKind;

import java.util.List;

/**
 * Named after the question it answers, never after the vendor that answers it — that is what lets a vendor be
 * swapped without changing what the evidence means. FR6 adds SCREENING, CREDIT and INCOME.
 */
public enum RequirementType {

    IDENTITY(List.of(DocumentKind.ID));

    private final List<DocumentKind> acceptedDocumentKinds;

    RequirementType(List<DocumentKind> acceptedDocumentKinds) {
        this.acceptedDocumentKinds = acceptedDocumentKinds;
    }

    /** What the applicant may upload to move this requirement forward. */
    public List<DocumentKind> acceptedDocumentKinds() {
        return acceptedDocumentKinds;
    }

    public boolean acceptsDocument(DocumentKind kind) {
        return acceptedDocumentKinds.contains(kind);
    }
}
