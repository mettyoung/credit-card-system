/**
 * Third-party checks: the queue, the workers, and one adapter per provider.
 * <p>
 * Does not depend on {@code application}. It needs the applicant's details for a call, and gets them through
 * {@code ApplicantSubjects} — a port declared here and implemented there — so the dependency points one way
 * even though the conversation goes both.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Vendor checks",
        allowedDependencies = { "audit", "document", "shared", "shared::outbox" })
package com.mettyoung.creditcardapplication.vendor;
