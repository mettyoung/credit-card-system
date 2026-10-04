package com.mettyoung.creditcardapplication.vendor.internal;

/**
 * The vendor's own handle for the person a check is about (Onfido's applicant id). Kept so a retried claim
 * reuses the person instead of registering them again, and so it can ask the vendor which checks already exist
 * for them.
 */
record VendorSubjectRef(String value) {

    public VendorSubjectRef {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("vendorSubjectRef must not be blank");
        }
    }
}
