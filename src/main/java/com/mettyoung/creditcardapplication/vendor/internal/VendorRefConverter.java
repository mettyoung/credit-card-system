package com.mettyoung.creditcardapplication.vendor.internal;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Loading goes through the {@link VendorRef} constructor, so a blank stored value fails on load. */
@Converter
class VendorRefConverter implements AttributeConverter<VendorRef, String> {

    @Override
    public String convertToDatabaseColumn(VendorRef attribute) {
        return attribute == null ? null : attribute.value();
    }

    @Override
    public VendorRef convertToEntityAttribute(String column) {
        return column == null ? null : new VendorRef(column);
    }
}
