package com.mettyoung.creditcardapplication.application.internal;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Loading goes through the {@link LastName} constructor, so a row that breaks the rules (e.g. written by a
 * manual fix or a migration) fails on load instead of flowing on as a valid name.
 */
@Converter
class LastNameConverter implements AttributeConverter<LastName, String> {

    @Override
    public String convertToDatabaseColumn(LastName name) {
        return name == null ? null : name.value();
    }

    @Override
    public LastName convertToEntityAttribute(String column) {
        return column == null ? null : new LastName(column);
    }
}
