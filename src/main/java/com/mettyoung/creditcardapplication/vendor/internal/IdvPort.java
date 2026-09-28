package com.mettyoung.creditcardapplication.vendor.internal;

import com.mettyoung.creditcardapplication.vendor.ApplicantSubjects;

import java.util.UUID;

/**
 * Identity verification, as this application needs it. The implementation talks to Onfido; nothing about
 * Onfido's own types appears in this signature, which is what lets Jumio replace it without touching a
 * requirement, a state machine or a rule.
 */
interface IdvPort {

    /**
     * Submits a document for verification. Asynchronous by nature: the expected answer is
     * {@link VendorResult.Pending}, and the result arrives by callback.
     *
     * @param key sent to the vendor so a retried claim is not charged twice
     */
    VendorResult<IdvOutcome> submit(ApplicantSubjects.Subject subject, UUID documentId, IdempotencyKey key);

    /** Fetches the result the callback only announced. */
    VendorResult<IdvOutcome> status(VendorRef ref);
}
