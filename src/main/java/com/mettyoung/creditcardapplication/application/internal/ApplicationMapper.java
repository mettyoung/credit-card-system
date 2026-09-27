package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.ApplicationResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

import java.time.LocalDate;
import java.util.List;

/**
 * Entity to response mapping, generated at compile time. The build sets unmappedTargetPolicy=ERROR, so a new
 * response field with no matching source fails compilation.
 * <p>
 * Lives here rather than nested in {@link ApplicationResponse}, because it names the value objects the
 * response flattens - and those stay inside the module.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
interface ApplicationMapper {

    ApplicationResponse toResponse(Application application);

    // One unwrapper per value object. MapStruct picks them by source type, so the two name parts
    // cannot be crossed even though both produce a String.
    default String firstName(FirstName name) {
        return name == null ? null : name.value();
    }

    default String lastName(LastName name) {
        return name == null ? null : name.value();
    }

    default LocalDate dateOfBirth(DateOfBirth dateOfBirth) {
        return dateOfBirth == null ? null : dateOfBirth.value();
    }

    default String country(Country country) {
        return country == null ? null : country.code();
    }
}
