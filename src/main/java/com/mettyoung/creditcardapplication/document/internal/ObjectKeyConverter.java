package com.mettyoung.creditcardapplication.document.internal;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Loading goes through the {@link ObjectKey} constructor, so a row that breaks the rules fails on load. */
@Converter
class ObjectKeyConverter implements AttributeConverter<ObjectKey, String> {

    @Override
    public String convertToDatabaseColumn(ObjectKey attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public ObjectKey convertToEntityAttribute(String column) {
        return column == null ? null : new ObjectKey(column);
    }
}
