package com.mettyoung.creditcardapplication.shared.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * The rows this relay may take: unpublished, and either never claimed or claimed by someone whose lease
     * has run out.
     * <p>
     * {@code FOR UPDATE SKIP LOCKED} keeps two relays from selecting the same rows in the instant between
     * this query and the update that records the claim. It does nothing after that transaction commits —
     * which is the whole reason the claim has to be a column rather than a lock.
     */
    @Query(value = """
            SELECT id FROM outbox
             WHERE published_at IS NULL
               AND (claimed_until IS NULL OR claimed_until < :now)
             ORDER BY id
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> selectClaimable(@Param("now") Instant now, @Param("limit") int limit);

    /** Records the claim, so the rows stay invisible to other relays after this transaction commits. */
    @Modifying
    @Query("update OutboxEvent e set e.claimedBy = :owner, e.claimedUntil = :until where e.id in :ids")
    int claim(@Param("owner") String owner, @Param("until") Instant until, @Param("ids") List<UUID> ids);

    /** Hands a row back after a failed dispatch, so the retry does not wait out the lease. */
    @Modifying
    @Query("update OutboxEvent e set e.claimedBy = null, e.claimedUntil = null"
            + " where e.id = :id and e.publishedAt is null")
    int releaseClaim(@Param("id") UUID id);
}
