package com.mettyoung.creditcardapplication.application.internal;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDate;

/**
 * Loading goes through the {@link DateOfBirth} constructor, so a row outside the allowed range fails on load
 * instead of flowing on as a valid date.
 */
@Converter
class DateOfBirthConverter implements AttributeConverter<DateOfBirth, LocalDate> {

    @Override
    public LocalDate convertToDatabaseColumn(DateOfBirth dateOfBirth) {
        return dateOfBirth == null ? null : dateOfBirth.value();
    }

    @Override
    public DateOfBirth convertToEntityAttribute(LocalDate column) {
        return column == null ? null : new DateOfBirth(column);
    }
}
