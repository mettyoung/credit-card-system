package com.mettyoung.creditcardapplication.application.internal;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import com.mettyoung.creditcardapplication.vendor.ApplicantSubjects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Implements the port the vendor module declares, so the applicant's details reach a vendor call without the
 * vendor module depending on this one.
 * <p>
 * This is also the one place declared data is turned into something that crosses the network — which makes it
 * the place to look when asking what leaves the system.
 */
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
class ApplicantSubjectLookup implements ApplicantSubjects {

    private final ApplicationRepository applications;


    @Override
    @Transactional(readOnly = true)
    public Optional<ApplicantSubjects.Subject> forApplication(UUID applicationId) {
        return applications.findById(applicationId).map(ApplicantSubjectLookup::toSubject);
    }

    private static ApplicantSubjects.Subject toSubject(Application application) {
        return new ApplicantSubjects.Subject(
                application.getFirstName() == null ? null : application.getFirstName().value(),
                application.getLastName() == null ? null : application.getLastName().value(),
                application.getDateOfBirth() == null ? null : application.getDateOfBirth().value(),
                application.getCountry() == null ? null : application.getCountry().code());
    }
}
