package com.mettyoung.creditcardapplication.vendor.internal;

/**
 * Three outcomes, not two: a vendor can answer, refuse, or accept the work and answer later. Collapsing
 * {@code Pending} into either of the others is what makes an async integration look synchronous and then
 * lose results.
 *
 * @param <T> the vendor's answer, in our vocabulary
 */
public sealed interface VendorResult<T> {

    /** @param adopted a lost attempt had already created this check; it was found, not created (FR11) */
    record Completed<T>(T value, String rawResponse, boolean adopted) implements VendorResult<T> {
        public Completed(T value, String rawResponse) {
            this(value, rawResponse, false);
        }
    }

    /** @param adopted a lost attempt had already created this check; it was found, not created (FR11) */
    record Pending<T>(VendorRef ref, String rawResponse, boolean adopted) implements VendorResult<T> {
        public Pending(VendorRef ref, String rawResponse) {
            this(ref, rawResponse, false);
        }
    }

    record Failed<T>(VendorFailure failure, String rawResponse) implements VendorResult<T> {
    }
}
