package com.mettyoung.creditcardapplication.vendor.internal;

import com.mettyoung.creditcardapplication.vendor.ApplicantSubjects;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Identity verification, as this application needs it. The implementation talks to Onfido; nothing about
 * Onfido's own types appears in this signature, which is what lets Jumio replace it without touching a
 * requirement, a state machine or a rule.
 */
interface IdvPort {

    /**
     * Submits a document for verification. Asynchronous by nature: the expected answer is
     * {@link VendorResult.Pending}, and the result arrives by callback.
     * <p>
     * A retry must not pay twice, and an idempotency key alone cannot promise that: Onfido ignores it. So the
     * person is registered first and handed to {@code onRegistered} to be stored, and a later attempt passes it
     * back as {@code registered} - the implementation then asks the vendor whether a check already exists for
     * them before creating another.
     *
     * @param registered   the vendor's handle for this person from an earlier attempt, or {@code null}
     * @param onRegistered stores a newly registered handle; called before the billed call, outside it
     * @param key          sent to the vendor in case it does deduplicate on it
     */
    VendorResult<IdvOutcome> submit(ApplicantSubjects.Subject subject, VendorSubjectRef registered,
                                    Consumer<VendorSubjectRef> onRegistered, UUID documentId,
                                    IdempotencyKey key);

    /** Fetches the result the callback only announced. */
    VendorResult<IdvOutcome> status(VendorRef ref);
}
