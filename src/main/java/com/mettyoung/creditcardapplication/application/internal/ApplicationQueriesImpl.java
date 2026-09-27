package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.ApplicationResponse;
import com.mettyoung.creditcardapplication.application.ApplicationStatus;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.function.Function;

/**
 * Spring Data finds this by the {@code Impl} suffix and wires it into the repository.
 * <p>
 * The metamodel is generated from {@link Application} itself, so a renamed field breaks this at compile time
 * rather than at runtime — which is the reason to reach for a query DSL rather than write the column names
 * out by hand.
 * <p>
 * It runs on the same {@code EntityManager} as everything else, so it sees the persistence context and needs
 * no rule about flushing before reading.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class ApplicationQueriesImpl implements ApplicationQueries {

    private static final QApplication APPLICATION = QApplication.application;

    private final JPAQueryFactory query;

    @Override
    public List<ApplicationResponse> listFor(String userId, ApplicationStatus status) {
        // An absent filter adds nothing rather than selecting a second query - the whole point of a DSL
        // here. Adding "created after" or a product filter is another line, not another method.
        BooleanBuilder where = new BooleanBuilder(APPLICATION.userId.eq(userId));
        if (status != null) {
            where.and(APPLICATION.status.eq(status));
        }

        // The columns, not the aggregate: a list never needs the entity hydrated.
        return query.select(APPLICATION.id, APPLICATION.cardProductCode, APPLICATION.status,
                        APPLICATION.firstName, APPLICATION.lastName, APPLICATION.dateOfBirth,
                        APPLICATION.country, APPLICATION.version)
                .from(APPLICATION)
                .where(where)
                .orderBy(APPLICATION.id.desc())
                .fetch().stream()
                .map(ApplicationQueriesImpl::toResponse)
                .toList();
    }

    private static ApplicationResponse toResponse(Tuple row) {
        return new ApplicationResponse(
                row.get(APPLICATION.id),
                row.get(APPLICATION.cardProductCode),
                row.get(APPLICATION.status),
                // The converters have already run, so these are value objects; flattening them is the same
                // unwrapping ApplicationMapper does for the single-application response.
                unwrap(row.get(APPLICATION.firstName), FirstName::value),
                unwrap(row.get(APPLICATION.lastName), LastName::value),
                unwrap(row.get(APPLICATION.dateOfBirth), DateOfBirth::value),
                unwrap(row.get(APPLICATION.country), Country::code),
                row.get(APPLICATION.version));
    }

    private static <T, R> R unwrap(T value, Function<T, R> of) {
        return value == null ? null : of.apply(value);
    }
}
