package com.mettyoung.creditcardapplication.vendor.internal;

import com.mettyoung.creditcardapplication.shared.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per call we intend to make to a vendor. The table <em>is</em> the job queue: inserting a row in the
 * orchestrator's transaction is how work is commanded, which means a command cannot be lost and cannot be
 * issued without the state change that asked for it.
 * <p>
 * Retries, leases and deadlines are all columns rather than in-memory timers, so a restart loses nothing.
 */
@Getter
@Entity
@Table(name = "vendor_check")
class VendorCheck {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private CheckType type;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "document_id", updatable = false)
    private UUID documentId;

    @Convert(converter = IdempotencyKeyConverter.class)
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private IdempotencyKey idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private CheckStatus status;

    @Convert(converter = VendorRefConverter.class)
    @Column(name = "vendor_ref")
    private VendorRef vendorRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome")
    private IdvOutcome outcome;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "raw_response")
    private String rawResponse;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    /** When the callback stops being worth waiting for. Set when the vendor accepts the work. */
    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected VendorCheck() {
        // for JPA
    }

    private VendorCheck(UUID applicationId, CheckType type, String provider, UUID documentId,
                        IdempotencyKey idempotencyKey, Instant now) {
        this.id = UuidV7.generate();
        this.applicationId = applicationId;
        this.type = type;
        this.provider = provider;
        this.documentId = documentId;
        this.idempotencyKey = idempotencyKey;
        this.status = CheckStatus.QUEUED;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    public static VendorCheck queueIdv(UUID applicationId, String provider, UUID documentId, Instant now) {
        return new VendorCheck(applicationId, CheckType.IDV, provider, documentId,
                IdempotencyKey.forIdv(applicationId, documentId), now);
    }

    /** Taken by a worker for {@code lease}; if that worker dies, the lease expires and the row is retried. */
    public void claim(Instant now, Duration lease) {
        status = CheckStatus.IN_PROGRESS;
        attempts += 1;
        leaseUntil = now.plus(lease);
    }

    /** The vendor answered. */
    public void complete(IdvOutcome outcome, String rawResponse, Instant now) {
        this.status = CheckStatus.COMPLETED;
        this.outcome = outcome;
        this.rawResponse = rawResponse;
        this.failureCode = null;
        this.leaseUntil = null;
        this.nextAttemptAt = now;
    }

    /** The vendor accepted the work and will call back, or we will poll, until {@code deadline}. */
    public void awaitCallback(VendorRef ref, String rawResponse, Instant now, Duration untilDeadline,
                              Duration pollAfter) {
        this.status = CheckStatus.AWAITING_CALLBACK;
        this.vendorRef = ref;
        this.rawResponse = rawResponse;
        this.leaseUntil = null;
        this.deadlineAt = now.plus(untilDeadline);
        this.nextAttemptAt = now.plus(pollAfter);
    }

    /** A retryable failure with attempts left. Backoff is {@code 2^attempts} seconds with jitter. */
    public void scheduleRetry(String failureCode, String rawResponse, Instant now, Duration backoff) {
        this.status = CheckStatus.RETRY;
        this.failureCode = failureCode;
        this.rawResponse = rawResponse;
        this.leaseUntil = null;
        this.nextAttemptAt = now.plus(backoff);
    }

    /** No answer will be obtained. The requirement becomes UNAVAILABLE, never a decline. */
    public void fail(String failureCode, String rawResponse, Instant now) {
        this.status = CheckStatus.FAILED;
        this.failureCode = failureCode;
        this.rawResponse = rawResponse;
        this.leaseUntil = null;
        this.nextAttemptAt = now;
    }

    /** Push the next poll out, so two reconciler passes cannot chase the same check at once. */
    public void scheduleNextPoll(Instant at) {
        this.nextAttemptAt = at;
    }

    public boolean hasAttemptsLeft(int maxAttempts) {
        return attempts < maxAttempts;
    }

    public boolean isPastDeadline(Instant now) {
        return deadlineAt != null && !now.isBefore(deadlineAt);
    }

    public boolean isAwaitingCallback() {
        return status == CheckStatus.AWAITING_CALLBACK;
    }
}
