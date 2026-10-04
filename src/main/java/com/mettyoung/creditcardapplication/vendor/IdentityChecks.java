package com.mettyoung.creditcardapplication.vendor;

import java.util.Optional;
import java.util.UUID;

/**
 * The vendor module's API for identity verification, as the workflow needs it.
 * <p>
 * The orchestrator commands through this instead of writing {@code vendor_check} rows itself. That is not
 * ceremony: reaching into another module's repository is what created an {@code application} to {@code vendor}
 * cycle, and it let the workflow depend on how a check is stored rather than on what a check is.
 */
public interface IdentityChecks {

    /**
     * Queues a check for this document. The row is written in the caller's transaction, so a command cannot
     * exist without the state change that asked for it.
     *
     * @return the new check's id
     */
    UUID queue(UUID applicationId, UUID documentId);

    /** The answer, once the check is terminal. Empty while it is still running. */
    Optional<CheckResult> resultOf(UUID checkId);

    /**
     * What a finished check amounts to, with no vendor vocabulary in it.
     *
     * @param needsAnotherDocument the vendor answered, but the applicant must supply something else
     * @param answered             an answer was obtained — including bad news, which is still an answer
     */
    record CheckResult(UUID checkId, boolean answered, boolean needsAnotherDocument, String outcome,
                       String failureCode) {
    }
}
