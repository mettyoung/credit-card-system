package com.mettyoung.creditcardapplication.audit.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByApplicationIdOrderBySeq(UUID applicationId);

    @Query("select max(e.seq) from AuditEvent e where e.applicationId = :applicationId")
    Optional<Long> findMaxSeq(UUID applicationId);
}
