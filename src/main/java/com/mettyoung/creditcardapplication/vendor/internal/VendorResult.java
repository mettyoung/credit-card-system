package com.mettyoung.creditcardapplication.vendor.internal;

/**
 * Three outcomes, not two: a vendor can answer, refuse, or accept the work and answer later. Collapsing
 * {@code Pending} into either of the others is what makes an async integration look synchronous and then
 * lose results.
 * <p>
 * Each carries the attempt's {@link Lookup}, failures included: a retry that found nothing and then failed to create
 * did both, and the audit log records both (FR11.6).
 *
 * @param <T> the vendor's answer, in our vocabulary
 */
public sealed interface VendorResult<T> {

    Lookup lookup();

    /** Whether this attempt looked for a check an earlier one created, and what it found (FR11.6). */
    enum Lookup {
        /** The subject was registered on this attempt, so no earlier check can exist. */
        NOT_NEEDED,
        /** Looked, found nothing; creating one was safe. */
        NONE_FOUND,
        /** Looked, found the check an earlier attempt created, and used it instead of creating another. */
        ADOPTED
    }

    record Completed<T>(T value, String rawResponse, Lookup lookup) implements VendorResult<T> {
        public Completed(T value, String rawResponse) {
            this(value, rawResponse, Lookup.NOT_NEEDED);
        }
    }

    record Pending<T>(VendorRef ref, String rawResponse, Lookup lookup) implements VendorResult<T> {
        public Pending(VendorRef ref, String rawResponse) {
            this(ref, rawResponse, Lookup.NOT_NEEDED);
        }
    }

    record Failed<T>(VendorFailure failure, String rawResponse, Lookup lookup) implements VendorResult<T> {
        public Failed(VendorFailure failure, String rawResponse) {
            this(failure, rawResponse, Lookup.NOT_NEEDED);
        }
    }
}
