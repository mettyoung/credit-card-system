package com.mettyoung.creditcardapplication.vendor.internal;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Loading goes through the {@link VendorSubjectRef} constructor, so a blank stored value fails on load. */
@Converter
class VendorSubjectRefConverter implements AttributeConverter<VendorSubjectRef, String> {

    @Override
    public String convertToDatabaseColumn(VendorSubjectRef attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public VendorSubjectRef convertToEntityAttribute(String column) {
        return column == null ? null : new VendorSubjectRef(column);
    }
}
