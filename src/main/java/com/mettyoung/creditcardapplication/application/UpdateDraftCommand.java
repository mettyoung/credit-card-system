package com.mettyoung.creditcardapplication.application;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * The whole declared-data form, saved as one unit: the request body of {@code PATCH /v1/applications/{id}}
 * and the module's input in one type. {@code version} is the version the client last read; it guards against
 * overwriting a newer save made from another tab or device, and travels with the data it guards rather than
 * as a second argument that a caller could pair with the wrong form.
 * <p>
 * Only {@code version} is annotated. The other fields' rules (blank, length, control characters, date range,
 * ISO country) live in the value objects, so they can't be bypassed by a caller that doesn't go through this
 * DTO. {@code dateOfBirth} is typed rather than a String only because Jackson already answers a malformed
 * date with the same {@code errors[{field, message}]} shape the value objects produce.
 */
public record UpdateDraftCommand(
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        String country,
        @NotNull Long version
) {
}
