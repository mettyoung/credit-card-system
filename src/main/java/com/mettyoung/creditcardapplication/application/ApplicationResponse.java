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
        long version
) {

    public record Page(List<ApplicationResponse> items) {
    }

}
