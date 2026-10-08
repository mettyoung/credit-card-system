package com.mettyoung.creditcardapplication.application.internal;

/**
 * Stand-in for an authenticated reviewer until auth exists, resolved from the X-Reviewer-Id header. Identifies,
 * does not authorise: anyone who sends the header can decide (FR8 §8).
 */
record ReviewerId(String value) {
}
