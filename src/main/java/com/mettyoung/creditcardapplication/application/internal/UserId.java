package com.mettyoung.creditcardapplication.application.internal;

/**
 * Stand-in for an authenticated principal until auth exists. Resolved from the X-User-Id header.
 */
record UserId(String value) {
}
