package com.mettyoung.creditcardapplication.application.internal

import com.mettyoung.creditcardapplication.application.UpdateDraftCommand
import com.mettyoung.creditcardapplication.application.CardProduct
import com.mettyoung.creditcardapplication.application.RequirementType
import com.mettyoung.creditcardapplication.document.Documents
import com.mettyoung.creditcardapplication.document.DocumentKind
import com.mettyoung.creditcardapplication.document.DocumentStatus
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The orchestrator's whole decision table, with no database, no clock and no container — which is the point of
 * keeping evaluate() pure. Most of FR4's and FR5's coverage lives here.
 */
class EvaluatorTest : DescribeSpec({

    val at = Instant.parse("2026-10-08T10:00:00Z")

    val born = LocalDate.of(1990, 4, 12)

    fun draft(): Application = Application.createDraft("u_1", CardProduct.CLASSIC).apply {
        on(UpdateDraftCommand("Jane", "Tan", born, "SG", 0L))
    }

    fun submitted(): Application = draft().apply { submit(true, at) }

    fun verifying(): Application = submitted().apply { startVerifying(at) }

    fun needsInfo(): Application = verifying().apply { requestInfo(at) }

    // createdAt is explicit: "newest" has to be unambiguous, and two documents created in the same
    // millisecond are not ordered by their ids.
    var uploadedAt = Instant.parse("2026-09-27T10:00:00Z")

    // The evaluator sees the document module's view, not its entity - so this builds four fields rather
    // than constructing an aggregate the decision never needed.
    fun document(kind: DocumentKind = DocumentKind.ID, uploaded: Boolean = true,
                 at: Instant? = null): Documents.Accepted {
        val createdAt = at ?: uploadedAt.also { uploadedAt = uploadedAt.plusSeconds(60) }
        val status = if (uploaded) DocumentStatus.UPLOADED else DocumentStatus.PENDING_UPLOAD
        return Documents.Accepted(UUID.randomUUID(), kind, status, createdAt)
    }

    fun identity(): EvidenceRequirement =
        EvidenceRequirement.pending(UUID.randomUUID(), RequirementType.IDENTITY)

    describe("an application that has just been submitted") {

        it("starts the identity check on the accepted ID document") {
            val id = document()

            val step = Evaluator.evaluate(submitted(), emptyList(), listOf(id))

            step.shouldBeInstanceOf<NextStep.StartIdentityCheck>().documentId() shouldBe id.id()
        }

        it("picks the newest accepted ID when several were uploaded") {
            val older = document()
            val newer = document()

            val step = Evaluator.evaluate(submitted(), emptyList(), listOf(older, newer))

            // UUIDv7 is time-ordered, so the greatest id is the latest upload.
            step.shouldBeInstanceOf<NextStep.StartIdentityCheck>().documentId() shouldBe newer.id()
        }

        it("ignores a document that was never accepted") {
            val pending = document(uploaded = false)

            Evaluator.evaluate(submitted(), emptyList(), listOf(pending))
                .shouldBeInstanceOf<NextStep.Wait>()
        }

        it("waits rather than throwing when no ID is there at all") {
            // submit() guarantees one, so this is unreachable through the API. Waiting keeps a surprising
            // state observable instead of turning the relay into a retry loop.
            Evaluator.evaluate(submitted(), emptyList(), emptyList())
                .shouldBeInstanceOf<NextStep.Wait>()
        }
    }

    describe("an application with a check in flight") {

        it("waits while its only requirement is still pending") {
            Evaluator.evaluate(verifying(), listOf(identity()), listOf(document()))
                .shouldBeInstanceOf<NextStep.Wait>()
        }

        it("completes once every requirement is settled") {
            val settled = identity().apply { receive(EvidenceSource.Vendor(UUID.randomUUID())) }

            Evaluator.evaluate(verifying(), listOf(settled), listOf(document()))
                .shouldBeInstanceOf<NextStep.Complete>()
        }

        it("completes when the only requirement is unavailable, so a dead vendor cannot hang it") {
            val unavailable = identity().apply { markUnavailable() }

            Evaluator.evaluate(verifying(), listOf(unavailable), listOf(document()))
                .shouldBeInstanceOf<NextStep.Complete>()
        }

        it("never completes with no requirements at all") {
            Evaluator.evaluate(verifying(), emptyList(), listOf(document()))
                .shouldBeInstanceOf<NextStep.Wait>()
        }
    }

    describe("an unreadable document") {

        it("asks the applicant for another, naming the requirement") {
            val needs = identity().apply { awaitCheckFor(document().id()); needEvidence() }

            val step = Evaluator.evaluate(verifying(), listOf(needs), emptyList())

            step.shouldBeInstanceOf<NextStep.RequestInfo>().missing() shouldBe
                listOf(RequirementType.IDENTITY)
        }

        it("starts a new check as soon as a different document is accepted") {
            val original = document()
            val replacement = document()
            val needs = identity().apply { awaitCheckFor(original.id()); needEvidence() }

            val step = Evaluator.evaluate(needsInfo(), listOf(needs), listOf(original, replacement))

            step.shouldBeInstanceOf<NextStep.StartIdentityCheck>().documentId() shouldBe replacement.id()
        }

        it("does not re-check the very document that was already rejected") {
            val original = document()
            // needEvidence() clears the source, so the guard is the one that matters: a requirement already
            // awaiting a check for this document must not queue a second paid call.
            val awaiting = identity().apply { awaitCheckFor(original.id()) }

            Evaluator.evaluate(verifying(), listOf(awaiting), listOf(original))
                .shouldBeInstanceOf<NextStep.Wait>()
        }

        it("ignores a payslip, which cannot answer identity") {
            val payslip = document(kind = DocumentKind.PAYSLIP)
            val needs = identity().apply { needEvidence() }

            Evaluator.evaluate(needsInfo(), listOf(needs), listOf(payslip))
                .shouldBeInstanceOf<NextStep.RequestInfo>()
        }
    }
})
