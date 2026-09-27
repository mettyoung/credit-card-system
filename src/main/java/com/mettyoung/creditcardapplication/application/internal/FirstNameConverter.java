package com.mettyoung.creditcardapplication.application.internal;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Loading goes through the {@link FirstName} constructor, so a row that breaks the rules (e.g. written by a
 * manual fix or a migration) fails on load instead of flowing on as a valid name.
 */
@Converter
class FirstNameConverter implements AttributeConverter<FirstName, String> {

    @Override
    public String convertToDatabaseColumn(FirstName name) {
        return name == null ? null : name.value();
    }

    @Override
    public FirstName convertToEntityAttribute(String column) {
        return column == null ? null : new FirstName(column);
    }
}
