package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.ApplicationStatus;
import com.mettyoung.creditcardapplication.application.RequirementStatus;
import com.mettyoung.creditcardapplication.application.RequirementType;
import com.mettyoung.creditcardapplication.document.Documents;
import com.mettyoung.creditcardapplication.document.DocumentKind;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The decision, as a pure function: no I/O, no clock, no randomness. That is what lets the whole table of
 * outcomes be a unit test rather than an integration test, and it is where most of FR4 and FR5's coverage lives.
 * <p>
 * Nothing here mutates anything. {@link ApplicationProcess} applies whatever this returns.
 */
final class Evaluator {

    private Evaluator() {
    }

    /**
     * @param requirements every requirement of the application, whatever its state
     * @param documents    every document of the application, as the document module exposes them
     */
    public static NextStep evaluate(Application application, List<EvidenceRequirement> requirements,
                                    List<Documents.Accepted> documents) {
        // Intake is recorded but nothing has been asked yet: start the identity check.
        if (application.getStatus() == ApplicationStatus.SUBMITTED) {
            return latestUploaded(documents, DocumentKind.ID)
                    .<NextStep>map(document -> new NextStep.StartIdentityCheck(document.id()))
                    // submit() guarantees an accepted ID exists, so this is unreachable through the API.
                    // Returning Wait rather than throwing keeps a surprising state observable instead of
                    // turning the relay into a retry loop.
                    .orElseGet(NextStep.Wait::new);
        }

        List<RequirementType> needingEvidence = requirements.stream()
                .filter(requirement -> requirement.getStatus() == RequirementStatus.NEEDS_EVIDENCE)
                .map(EvidenceRequirement::getType)
                .toList();

        // A requirement that needs a document and has a fresh one waiting: start a new check for it.
        for (EvidenceRequirement requirement : requirements) {
            if (requirement.getStatus() != RequirementStatus.NEEDS_EVIDENCE) {
                continue;
            }
            Optional<Documents.Accepted> fresh = requirement.getType().acceptedDocumentKinds().stream()
                    .flatMap(kind -> latestUploaded(documents, kind).stream())
                    .max(Comparator.comparing(Documents.Accepted::createdAt).thenComparing(Documents.Accepted::id));
            if (fresh.isPresent() && !alreadyChecked(requirement, fresh.get())) {
                return new NextStep.StartIdentityCheck(fresh.get().id());
            }
        }

        if (!needingEvidence.isEmpty()) {
            return new NextStep.RequestInfo(needingEvidence);
        }

        boolean allSettled = !requirements.isEmpty()
                && requirements.stream().allMatch(EvidenceRequirement::isSettled);
        return allSettled ? new NextStep.Complete() : new NextStep.Wait();
    }

    /**
     * This requirement already acted on this document — it is either awaiting its check or was told the
     * document is unusable. Either way a second check would be a duplicate paid call for evidence we already
     * have an answer about.
     */
    private static boolean alreadyChecked(EvidenceRequirement requirement, Documents.Accepted document) {
        return requirement.getSourceDocumentId() != null
                && requirement.getSourceDocumentId().equals(document.id());
    }

    /**
     * Ordered by {@code createdAt}, not by id. UUIDv7 is time-ordered <em>across</em> milliseconds but
     * arbitrary within one, so two uploads in the same millisecond could otherwise resolve backwards. The id
     * is the tiebreak, which only makes the choice deterministic, not meaningful — either document is a
     * defensible answer at that point.
     */
    private static Optional<Documents.Accepted> latestUploaded(List<Documents.Accepted> documents,
                                                             DocumentKind kind) {
        return documents.stream()
                .filter(document -> document.kind() == kind && document.isSendable())
                .max(Comparator.comparing(Documents.Accepted::createdAt).thenComparing(Documents.Accepted::id));
    }
}
