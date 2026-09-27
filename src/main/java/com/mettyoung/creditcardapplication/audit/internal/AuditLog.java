package com.mettyoung.creditcardapplication.audit.internal;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import com.mettyoung.creditcardapplication.audit.AuditEntry;
import com.mettyoung.creditcardapplication.audit.Audits;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;

/**
 * Appends to the log using the caller's transaction, so a change that committed is a change that was logged.
 * <p>
 * {@code MANDATORY}, not the absence of an annotation. Without one this would still be transactional —
 * {@code SimpleJpaRepository.save} is — so a call with no ambient transaction would open its own and commit
 * the audit row <em>independently</em> of the change it describes. That is the one failure this class exists to
 * prevent, and it would happen silently. {@code MANDATORY} makes it an exception at the first such call
 * instead.
 * <p>
 * {@code seq} is {@code max + 1}. That read-then-write is safe because every audit write happens in a
 * transaction that also touches the application row and therefore holds its optimistic lock, so two writers
 * for one application are already serialised. The unique index is the backstop that turns a mistake into a
 * failed transaction rather than a duplicate.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class AuditLog implements Audits {

    private final AuditEventRepository repository;
    private final ObjectMapper json;
    private final Clock clock;


    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        long seq = repository.findMaxSeq(entry.applicationId()).orElse(0L) + 1;
        repository.save(AuditEvent.of(entry.applicationId(), seq, entry.type(), entry.actor(), entry.actorId(),
                json.writeValueAsString(entry.payload()), Instant.now(clock)));
    }
}
