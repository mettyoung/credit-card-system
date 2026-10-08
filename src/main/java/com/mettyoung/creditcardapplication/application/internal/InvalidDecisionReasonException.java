package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.shared.DomainException;

/** FR8.3: a decline must name a reviewer's reason. Describes the rule, never the value sent. */
class InvalidDecisionReasonException extends DomainException {

    public InvalidDecisionReasonException() {
        super(Category.INVALID_VALUE, "A decline needs a reviewer's reason.");
    }
}
