package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.RequirementType;
import com.mettyoung.creditcardapplication.document.DocumentKind;

import java.util.List;

/** The accepted kinds as names, so the web layer never has to import the document module's enum. */
final class DocumentKindNames {

    private DocumentKindNames() {
    }

    public static List<String> of(RequirementType type) {
        return type.acceptedDocumentKinds().stream().map(DocumentKind::name).toList();
    }
}
