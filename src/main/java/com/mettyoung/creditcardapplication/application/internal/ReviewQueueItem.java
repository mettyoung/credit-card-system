package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.CardProduct;
import com.mettyoung.creditcardapplication.application.DecisionReason;

import java.time.Instant;
import java.util.UUID;

/**
 * A referred application, as a reviewer needs it to pick one up: why it was referred, how long it has waited,
 * and the version to send back. Public only because Jackson serialises it.
 *
 * @param overdue waiting longer than the referral deadline (FR9.2) - flagged, never closed automatically
 */
public record ReviewQueueItem(UUID id, CardProduct cardProductCode, DecisionReason decisionReason,
                              Instant referredAt, boolean overdue, long version) {
}
