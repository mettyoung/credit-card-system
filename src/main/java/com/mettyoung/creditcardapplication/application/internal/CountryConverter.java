package com.mettyoung.creditcardapplication.application.internal;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Loading goes through the {@link Country} constructor, so a row holding a code that is no longer ISO fails
 * on load instead of flowing on as a valid country.
 */
@Converter
class CountryConverter implements AttributeConverter<Country, String> {

    @Override
    public String convertToDatabaseColumn(Country country) {
        return country == null ? null : country.code();
    }

    @Override
    public Country convertToEntityAttribute(String column) {
        return column == null ? null : new Country(column);
    }
}
