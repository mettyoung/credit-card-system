package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.ApplicationStatus;
import com.mettyoung.creditcardapplication.application.CardProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ApplicationRepository extends JpaRepository<Application, UUID>, ApplicationQueries {

    Optional<Application> findByIdAndUserId(UUID id, String userId);

    /** The application holding the user's slot for this product. At most one, by ux_application_one_live. */
    Optional<Application> findFirstByUserIdAndCardProductCodeAndStatusNotIn(String userId, CardProduct cardProductCode,
                                                                           Collection<ApplicationStatus> statuses);

    /** FR8.2: the review queue, oldest first - ids are UUIDv7, so id order is creation order. */
    List<Application> findByStatusOrderByIdAsc(ApplicationStatus status);

    /** FR9: applications that entered this status before the cutoff - the sweep's query. */
    List<Application> findByStatusAndStatusChangedAtBefore(ApplicationStatus status, Instant cutoff);

    long countByStatusAndStatusChangedAtBefore(ApplicationStatus status, Instant cutoff);
}
