package com.mettyoung.creditcardapplication.shared.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEvent.Key> {
}
