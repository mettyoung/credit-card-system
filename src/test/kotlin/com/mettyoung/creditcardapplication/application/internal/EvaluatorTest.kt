package com.mettyoung.creditcardapplication.application.internal

import com.mettyoung.creditcardapplication.application.UpdateDraftCommand
import com.mettyoung.creditcardapplication.application.CardProduct
import com.mettyoung.creditcardapplication.application.RequirementType
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate
import java.util.UUID

/**
 * The orchestrator's whole decision table, with no database, no clock and no container — which is the point of
 * keeping evaluate() pure. Most of this increment's and FR5's coverage lives here.
 */
class EvaluatorTest : DescribeSpec({

    val born = LocalDate.of(1990, 4, 12)

    fun draft(): Application = Application.createDraft("u_1", CardProduct.CLASSIC).apply {
        on(UpdateDraftCommand("Jane", "Tan", born, "SG", 0L))
    }

    fun submitted(): Application = draft().apply { submit(true) }

    fun verifying(): Application = submitted().apply { startVerifying() }

    fun identity(): EvidenceRequirement =
        EvidenceRequirement.pending(UUID.randomUUID(), RequirementType.IDENTITY)

    describe("an application that has just been submitted") {

        it("waits, because no check is plugged into the spine yet") {
            // FR5 is what makes this return StartIdentityCheck. Until then waiting is the correct answer, not
            // a gap: the machinery exists and nothing has been attached to it.
            Evaluator.evaluate(submitted(), emptyList())
                .shouldBeInstanceOf<NextStep.Wait>()
        }
    }

    describe("an application gathering evidence") {

        it("waits while its only requirement is still pending") {
            Evaluator.evaluate(verifying(), listOf(identity()))
                .shouldBeInstanceOf<NextStep.Wait>()
        }

        it("completes once every requirement is settled") {
            val settled = identity().apply { receive(EvidenceSource.Vendor(UUID.randomUUID())) }

            Evaluator.evaluate(verifying(), listOf(settled))
                .shouldBeInstanceOf<NextStep.Complete>()
        }

        it("completes when the only requirement is unavailable, so a dead vendor cannot hang it") {
            val unavailable = identity().apply { markUnavailable() }

            Evaluator.evaluate(verifying(), listOf(unavailable))
                .shouldBeInstanceOf<NextStep.Complete>()
        }

        it("never completes with no requirements at all") {
            // An empty list means un-started, not finished. Completing here would make a missing row look
            // like a settled one.
            Evaluator.evaluate(verifying(), emptyList())
                .shouldBeInstanceOf<NextStep.Wait>()
        }
    }

    describe("a requirement that needs evidence") {

        it("asks the applicant for more, naming the requirement") {
            val needs = identity().apply { needEvidence() }

            val step = Evaluator.evaluate(verifying(), listOf(needs))

            step.shouldBeInstanceOf<NextStep.RequestInfo>().missing() shouldBe
                listOf(RequirementType.IDENTITY)
        }
    }
})
