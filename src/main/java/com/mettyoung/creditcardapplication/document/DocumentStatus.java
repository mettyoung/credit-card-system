package com.mettyoung.creditcardapplication.document;

public enum DocumentStatus {
    /** URL issued, bytes not confirmed. */
    PENDING_UPLOAD,
    /** Object present and verified against what was declared. */
    UPLOADED,
    /** Object present but size, checksum or magic bytes disagreed with the declaration. */
    INVALID,
    /** Never completed within the window; the object, if any, has been deleted. */
    EXPIRED
}
