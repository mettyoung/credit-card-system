package com.mettyoung.creditcardapplication.vendor.internal;

/** The vendor's own handle for a job in flight. Opaque to us; the only thing a webhook lets us look up. */
record VendorRef(String value) {

    public VendorRef {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("vendorRef must not be blank");
        }
    }
}
