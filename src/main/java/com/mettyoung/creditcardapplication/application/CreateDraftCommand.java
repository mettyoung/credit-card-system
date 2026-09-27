package com.mettyoung.creditcardapplication.application;

import jakarta.validation.constraints.NotNull;

/**
 * What to create. The request body of {@code POST /v1/applications} and the module's input in one type, so
 * nothing is copied field by field on the way in.
 */
public record CreateDraftCommand(@NotNull CardProduct cardProductCode) {
}
