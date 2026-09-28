package com.mettyoung.creditcardapplication.vendor.internal;

/**
 * Three outcomes, not two: a vendor can answer, refuse, or accept the work and answer later. Collapsing
 * {@code Pending} into either of the others is what makes an async integration look synchronous and then
 * lose results.
 *
 * @param <T> the vendor's answer, in our vocabulary
 */
public sealed interface VendorResult<T> {

    record Completed<T>(T value, String rawResponse) implements VendorResult<T> {
    }

    record Pending<T>(VendorRef ref, String rawResponse) implements VendorResult<T> {
    }

    record Failed<T>(VendorFailure failure, String rawResponse) implements VendorResult<T> {
    }
}
