package com.mettyoung.creditcardapplication.vendor.internal;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Loading goes through the {@link IdempotencyKey} constructor, so a blank stored value fails on load. */
@Converter
class IdempotencyKeyConverter implements AttributeConverter<IdempotencyKey, String> {

    @Override
    public String convertToDatabaseColumn(IdempotencyKey attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public IdempotencyKey convertToEntityAttribute(String column) {
        return column == null ? null : new IdempotencyKey(column);
    }
}
