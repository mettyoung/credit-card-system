package com.mettyoung.creditcardapplication.application.internal;

import java.util.UUID;

/**
 * What answered a requirement. Sealed, so adding a third kind of source forces every reader to say what it
 * means rather than defaulting.
 */
public sealed interface EvidenceSource {

    UUID id();

    /** A vendor answered. */
    record Vendor(UUID id) implements EvidenceSource {
    }

    /** The applicant's own document answered — FR6's payslip path. */
    record Upload(UUID id) implements EvidenceSource {
    }
}
