package com.mettyoung.creditcardapplication.vendor.internal;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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
     * <p>
     * Enum literals are unqualified: Hibernate 6+ infers the enum from {@code c.status}, and still rejects a
     * name that is not a constant when the repository starts.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select c from VendorCheck c
            where (c.status in (QUEUED, RETRY) and c.nextAttemptAt <= :now)
               or (c.status = IN_PROGRESS and c.leaseUntil < :now)
            order by c.nextAttemptAt
            """)
    List<VendorCheck> claimDue(Instant now, Limit limit);

    /** Callbacks that have gone quiet: poll them, and fail them once past the deadline. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select c from VendorCheck c
            where c.status = AWAITING_CALLBACK
              and c.nextAttemptAt <= :now
            order by c.nextAttemptAt
            """)
    List<VendorCheck> claimStaleCallbacks(Instant now, Limit limit);

    // By the value object, not by a nested property: VendorRef is a converted basic type, so
    // `findByVendorRefValue` would be read as a join on a non-existent association.
    Optional<VendorCheck> findByVendorRef(VendorRef vendorRef);

    /**
     * Completes the check only if it is still waiting for its callback. The inbox worker and the reconciler race on
     * the same check by design; the database evaluates the condition and the write together, so exactly one of them
     * changes the row and the other updates 0 rows and moves on. A read, a check in Java and a save could not do
     * that: both racers would pass the check, and the loser would fail on the version instead.
     * <p>
     * Sets what {@link VendorCheck#complete} sets, and bumps the version so a concurrent save of the same row still
     * conflicts. Clears the persistence context, so no stale copy of the row outlives the update.
     *
     * @return 1 if this call completed it, 0 if something else got there first
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update VendorCheck c
               set c.status = COMPLETED, c.outcome = :outcome, c.rawResponse = :rawResponse, c.failureCode = null,
                   c.leaseUntil = null, c.nextAttemptAt = :now, c.version = c.version + 1
             where c.id = :id and c.status = AWAITING_CALLBACK
            """)
    int completeIfAwaiting(UUID id, IdvOutcome outcome, String rawResponse, Instant now);

    /**
     * The reconciler ends a callback that never came, on the same terms as {@link #completeIfAwaiting}: only if it is
     * still waiting, so a late webhook and the deadline cannot both settle it. Sets what {@link VendorCheck#fail} sets.
     *
     * @return 1 if this call failed it, 0 if it was already settled
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update VendorCheck c
               set c.status = FAILED, c.failureCode = :failureCode, c.leaseUntil = null, c.nextAttemptAt = :now,
                   c.version = c.version + 1
             where c.id = :id and c.status = AWAITING_CALLBACK
            """)
    int failIfAwaiting(UUID id, String failureCode, Instant now);

    List<VendorCheck> findByApplicationId(UUID applicationId);

    long countByApplicationIdAndStatus(UUID applicationId, CheckStatus status);
}
