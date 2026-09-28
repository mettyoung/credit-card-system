package com.mettyoung.creditcardapplication.vendor.internal;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface VendorCheckRepository extends JpaRepository<VendorCheck, UUID> {

    /**
     * The claim query. Picks up work that is due, and work whose worker died — a lease that has expired is
     * indistinguishable from a crash, which is exactly the point.
     * <p>
     * {@code SKIP LOCKED} (the -2 lock timeout) lets many workers and many instances poll the same table with
     * no coordination: whoever locks a row first owns it, and the others move on instead of blocking.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select c from VendorCheck c
            where (c.status in (com.mettyoung.creditcardapplication.vendor.internal.CheckStatus.QUEUED,
                                com.mettyoung.creditcardapplication.vendor.internal.CheckStatus.RETRY)
                   and c.nextAttemptAt <= :now)
               or (c.status = com.mettyoung.creditcardapplication.vendor.internal.CheckStatus.IN_PROGRESS
                   and c.leaseUntil < :now)
            order by c.nextAttemptAt
            """)
    List<VendorCheck> claimDue(Instant now, Limit limit);

    /** Callbacks that have gone quiet: poll them, and fail them once past the deadline. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select c from VendorCheck c
            where c.status = com.mettyoung.creditcardapplication.vendor.internal.CheckStatus.AWAITING_CALLBACK
              and c.nextAttemptAt <= :now
            order by c.nextAttemptAt
            """)
    List<VendorCheck> claimStaleCallbacks(Instant now, Limit limit);

    // By the value object, not by a nested property: VendorRef is a converted basic type, so
    // `findByVendorRefValue` would be read as a join on a non-existent association.
    Optional<VendorCheck> findByVendorRef(VendorRef vendorRef);

    List<VendorCheck> findByApplicationId(UUID applicationId);

    long countByApplicationIdAndStatus(UUID applicationId, CheckStatus status);
}
