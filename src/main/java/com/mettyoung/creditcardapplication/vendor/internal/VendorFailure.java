package com.mettyoung.creditcardapplication.vendor.internal;

/**
 * Why no answer was obtained. Sealed so {@link #retryable()} is exhaustive: a new failure kind cannot be
 * added without deciding whether retrying it could ever help.
 */
public sealed interface VendorFailure {

    record Timeout() implements VendorFailure {
    }

    record Unavailable(int status) implements VendorFailure {
    }

    /** The vendor has no record of this subject. Retrying cannot conjure one. */
    record SubjectNotFound() implements VendorFailure {
    }

    /** Our request was wrong, or our credentials are. Retrying sends the same wrong request. */
    record InvalidRequest(String reason) implements VendorFailure {
    }

    default boolean retryable() {
        return switch (this) {
            case Timeout ignored -> true;
            case Unavailable ignored -> true;
            case SubjectNotFound ignored -> false;
            case InvalidRequest ignored -> false;
        };
    }

    default String code() {
        return switch (this) {
            case Timeout ignored -> "timeout";
            case Unavailable unavailable -> "unavailable-" + unavailable.status();
            case SubjectNotFound ignored -> "subject-not-found";
            case InvalidRequest ignored -> "invalid-request";
        };
    }
}
