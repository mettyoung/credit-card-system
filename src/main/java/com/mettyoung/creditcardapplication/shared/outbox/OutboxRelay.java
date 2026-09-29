package com.mettyoung.creditcardapplication.shared.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Turns committed outbox rows into calls on the orchestrator. One transaction per event: claim the row, insert
 * the {@code processed_event} marker, let the process apply it, mark published, commit.
 * <p>
 * A programmatic {@link TransactionTemplate} rather than {@code @Transactional}, for the same reason
 * {@code ApplicationService} uses one: a lost optimistic-lock race has to be caught <em>after</em> the rollback,
 * and {@code @Transactional} would raise {@code UnexpectedRollbackException} at commit instead.
 */
@Component
public class OutboxRelay {

    static final String CONSUMER = "application-process";
    private static final int BATCH = 20;

    /**
     * How long a claim holds. Long enough that a dispatch finishes inside it, short enough that a relay
     * killed mid-batch returns its rows soon. A successful or failed dispatch releases the claim outright;
     * this is only the backstop for the instance that never comes back.
     */
    private static final Duration LEASE = Duration.ofMinutes(1);

    /** Who this instance is, so a claim can name an owner. Nothing reads it back; it is for the operator. */
    private final String owner = "relay-" + java.util.UUID.randomUUID();

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final ProcessedEventRepository processed;
    private final DomainEventListener listener;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final Clock clock;

    OutboxRelay(OutboxRepository outbox, ProcessedEventRepository processed, DomainEventListener listener,
                ObjectMapper json, PlatformTransactionManager transactionManager, Clock clock) {
        this.outbox = outbox;
        this.processed = processed;
        this.listener = listener;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.workers.outbox-relay-interval:1s}")
    public int dispatchDue() {
        // Claim in its own transaction, then dispatch each row in its own, so one poisonous event cannot
        // block the rest of the batch.
        // Claiming is a write, not a lock: the row lock dies with this transaction, so the claim has to
        // outlive it as a column. Without that, another relay re-selects these rows the moment we commit and
        // does the same work — which only processed_event would then catch, after both had run.
        Instant now = clock.instant();
        List<java.util.UUID> ids = transaction.execute(status -> {
            List<java.util.UUID> claimable = outbox.selectClaimable(now, BATCH);
            if (!claimable.isEmpty()) {
                outbox.claim(owner, now.plus(LEASE), claimable);
            }
            return claimable;
        });
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int dispatched = 0;
        for (java.util.UUID id : ids) {
            if (dispatchOne(id)) {
                dispatched++;
            }
        }
        return dispatched;
    }

    /** In its own transaction: the one that failed has already rolled back. */
    private void release(java.util.UUID id) {
        transaction.execute(status -> outbox.releaseClaim(id));
    }

    private boolean dispatchOne(java.util.UUID id) {
        try {
            return transaction.execute(status -> {
                OutboxEvent event = outbox.findById(id).orElse(null);
                if (event == null || event.getPublishedAt() != null) {
                    return false;
                }
                // A processed_event row per (consumer, event) is how this consumer records that it has
                // handled the event - the consumer name makes the table behave like a consumer group, so a
                // second consumer added later gets its own row and sees every event independently.
                //
                // It is meant to be the guard against a redelivery running twice: the composite primary key
                // refuses the second insert and rolls the whole transaction back before the listener can run
                // again. It does not do that today. save() sees an assigned id, treats the entity as
                // existing, and merges - so a redelivery quietly overwrites processed_at instead. What
                // actually stops a second run is the publishedAt check above, which only works because one
                // relay polls at a time. Fixing it means an explicit INSERT ... ON CONFLICT DO NOTHING.
                processed.save(new ProcessedEvent(CONSUMER, event.getId(), clock.instant()));
                listener.on(json.readValue(event.getPayload(), typeOf(event.getType())));
                event.markPublished(clock.instant());
                outbox.save(event);
                return true;
            });
        } catch (OptimisticLockingFailureException e) {
            // Two results for one application landed together. The row stays unpublished; handing the claim
            // back is what lets the next poll retry it rather than waiting out the lease.
            log.debug("Outbox event {} lost a race; will retry", id);
            release(id);
            return false;
        } catch (DataIntegrityViolationException e) {
            // Some constraint in this transaction refused a row. Two can reach here:
            //
            //  - a second relay inserting the same processed_event marker, which needs another instance and
            //    a first insert racing rather than an already-present row;
            //  - a unique index violated inside listener.on - most plausibly ux_audit_app_seq, when an API
            //    request writes an audit row for the same application while this dispatch does. That one
            //    needs no second relay at all, so it is the likelier of the two here.
            //
            // Either way the transaction rolled back and nothing took effect, so retrying from the start is
            // safe. The constraint name is worth having: these should be rare, and a burst of them means
            // something is contending that was not expected to.
            log.debug("Outbox event {} hit a constraint; will retry", id, e);
            release(id);
            return false;
        }
    }

    /** Explicit, because the stored type name is a contract with rows written by older versions. */
    private static Class<? extends DomainEvent> typeOf(String type) {
        return switch (type) {
            case "ApplicationSubmitted" -> DomainEvent.ApplicationSubmitted.class;
            case "DocumentUploaded" -> DomainEvent.DocumentUploaded.class;
            case "ChecksCompleted" -> DomainEvent.ChecksCompleted.class;
            default -> throw new IllegalStateException("Unknown outbox event type: " + type);
        };
    }
}
