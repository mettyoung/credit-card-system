package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.CardProduct;
import com.mettyoung.creditcardapplication.application.DecisionReason;

import java.util.UUID;

/**
 * A referred application, as a reviewer needs it to pick one up: why it was referred, and the version to send
 * back. Public only because Jackson serialises it.
 */
public record ReviewQueueItem(UUID id, CardProduct cardProductCode, DecisionReason decisionReason, long version) {
}
