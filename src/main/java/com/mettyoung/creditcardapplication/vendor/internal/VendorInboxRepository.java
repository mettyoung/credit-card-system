package com.mettyoung.creditcardapplication.vendor.internal;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;
import java.util.UUID;

interface VendorInboxRepository extends JpaRepository<VendorInboxEvent, UUID> {

    boolean existsByVendorAndEventId(String vendor, String eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select e from VendorInboxEvent e where e.processedAt is null order by e.id")
    List<VendorInboxEvent> claimUnprocessed(Limit limit);

    long countByVendorAndEventId(String vendor, String eventId);
}
