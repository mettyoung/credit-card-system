package com.mettyoung.creditcardapplication.vendor.internal;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import com.mettyoung.creditcardapplication.vendor.IdentityChecks;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/** The only way in or out of the vendor module for the workflow. */
@Service
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class IdentityCheckService implements IdentityChecks {

    static final String PROVIDER_ONFIDO = "onfido";

    private final VendorCheckRepository checks;
    private final Clock clock;


    /** MANDATORY: the caller's transaction is the point — the command commits with the decision. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID queue(UUID applicationId, UUID documentId) {
        VendorCheck check = VendorCheck.queueIdv(applicationId, PROVIDER_ONFIDO, documentId, clock.instant());
        return checks.save(check).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CheckResult> resultOf(UUID checkId) {
        return checks.findById(checkId)
                .filter(check -> check.getStatus().isTerminal())
                .map(check -> new CheckResult(
                        check.getId(),
                        // COMPLETED means the vendor answered, whatever it said. FAILED means it did not.
                        check.getStatus() == CheckStatus.COMPLETED,
                        check.getOutcome() != null && check.getOutcome().needsAnotherDocument(),
                        check.getOutcome() == null ? null : check.getOutcome().name(),
                        check.getFailureCode()));
    }
}
