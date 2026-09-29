package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.ApplicationResponse;
import com.mettyoung.creditcardapplication.application.ApplicationStatus;

import java.util.List;

/**
 * The reads whose answer is a projection rather than an aggregate.
 *
 * Spring Data's derived methods need one signature per combination of predicates, which is why listing had
 * two of them for a single optional filter. These are written with Querydsl instead, so a predicate that may
 * or may not apply is a branch in one query rather than a second method.
 * <p>
 * A fragment of {@link ApplicationRepository}, so the service still has one collaborator for reading
 * applications and does not have to know which of them is a derived method and which is a query.
 */
interface ApplicationQueries {

    /**
     * Newest first. UUIDv7 ids are time-ordered, so id order is creation order and no {@code created_at}
     * column is needed.
     *
     * @param status optional filter; null means every status
     */
    List<ApplicationResponse.Summary> listFor(String userId, ApplicationStatus status);
}
