package com.mettyoung.creditcardapplication.application.internal;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** As {@link UserIdConverter}: null for a blank header, so blank and absent are both answered with 401. */
@Component
class ReviewerIdConverter implements Converter<String, ReviewerId> {

    @Override
    public ReviewerId convert(String source) {
        return source.isBlank() ? null : new ReviewerId(source.strip());
    }
}
