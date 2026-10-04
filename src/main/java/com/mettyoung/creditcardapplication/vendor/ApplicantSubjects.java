package com.mettyoung.creditcardapplication.vendor;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the vendor module gets the applicant's details.
 * <p>
 * Declared here and implemented by the module that owns the applicant, so {@code vendor} does not depend on
 * {@code application} — which would be a cycle, since the workflow commands the vendor. It also keeps declared
 * data out of {@code vendor_check}: the worker fetches it at the moment of the call rather than storing a copy.
 */
public interface ApplicantSubjects {

    Optional<Subject> forApplication(UUID applicationId);

    /**
     * What a vendor needs to know about the applicant. Declared data crosses the network from here and
     * nowhere else, so this is the one type to read when asking what leaves the system.
     */
    record Subject(String firstName, String lastName, LocalDate dateOfBirth, String country) {
    }
}
