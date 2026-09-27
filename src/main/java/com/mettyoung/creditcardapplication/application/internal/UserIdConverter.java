package com.mettyoung.creditcardapplication.application.internal;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Returning null for a blank header makes Spring raise MissingRequestHeaderException,
 * so blank and absent X-User-Id are both answered with 401.
 */
@Component
class UserIdConverter implements Converter<String, UserId> {

    @Override
    public UserId convert(String source) {
        return source.isBlank() ? null : new UserId(source.strip());
    }
}
