package com.mettyoung.creditcardapplication.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ApplicationResponse(
        UUID id,
        CardProduct cardProductCode,
        ApplicationStatus status,
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        String country,
        long version,
        List<RequirementResponse> requirements
) {

    /**
     * List items carry no requirements: loading them for every row would be an N+1, and a field that is
     * always empty teaches a client to ignore it.
     */
    public record Summary(UUID id, CardProduct cardProductCode, ApplicationStatus status, String firstName,
                          String lastName, LocalDate dateOfBirth, String country, long version) {
    }

    public record Page(List<Summary> items) {
    }
}
