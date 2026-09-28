package com.mettyoung.creditcardapplication.document.internal;

import com.mettyoung.creditcardapplication.document.Sha256;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Loading goes through the {@link Sha256} constructor, so a row that breaks the rules fails on load. */
@Converter
class Sha256Converter implements AttributeConverter<Sha256, String> {

    @Override
    public String convertToDatabaseColumn(Sha256 attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public Sha256 convertToEntityAttribute(String column) {
        return column == null ? null : new Sha256(column);
    }
}
