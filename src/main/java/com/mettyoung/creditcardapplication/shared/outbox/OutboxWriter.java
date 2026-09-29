package com.mettyoung.creditcardapplication.shared.outbox;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;

/**
 * Writes an event into the outbox using the caller's transaction, which is the point: the row commits with
 * the state change or not at all.
 * <p>
 * {@code MANDATORY} is what enforces that. Leaving the annotation off would not: the repository save is
 * transactional by itself, so a call with no ambient transaction would open one and commit an event describing
 * a change that has not happened yet, and might never.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper json;
    private final Clock clock;


    @Transactional(propagation = Propagation.MANDATORY)
    public void write(DomainEvent event) {
        Instant now = Instant.now(clock);
        repository.save(OutboxEvent.of(event, json.writeValueAsString(event), now));
    }
}
