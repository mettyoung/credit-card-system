package com.mettyoung.creditcardapplication.application.internal

import com.mettyoung.creditcardapplication.application.UpdateDraftCommand
import com.mettyoung.creditcardapplication.application.ApplicationStatus
import com.mettyoung.creditcardapplication.application.CardProduct
import com.mettyoung.creditcardapplication.application.DecisionReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class ApplicationTest : DescribeSpec({

    val born = LocalDate.of(1990, 4, 12)

    fun draft() = Application.createDraft("u_1", CardProduct.CLASSIC)

    describe("createDraft") {

        it("starts in DRAFT, owned by the applicant, with no declared data and no version yet") {
            val application = Application.createDraft("u_1", CardProduct.PLATINUM)

            application.id.version() shouldBe 7
            application.userId shouldBe "u_1"
            application.cardProductCode shouldBe CardProduct.PLATINUM
            application.status shouldBe ApplicationStatus.DRAFT
            application.firstName.shouldBeNull()
            application.lastName.shouldBeNull()
            application.dateOfBirth.shouldBeNull()
            application.country.shouldBeNull()
            application.version.shouldBeNull()
        }
    }

    describe("on(UpdateDraftCommand)") {

        it("stores all four fields, stripped and normalised") {
            val application = draft()

            application.on(UpdateDraftCommand("  Jane ", " Tan  ", born, "sg", 0L))

            application.firstName shouldBe FirstName("Jane")
            application.lastName shouldBe LastName("Tan")
            application.dateOfBirth shouldBe DateOfBirth(born)
            application.country shouldBe Country("SG")
        }

        it("keeps the values when the same form is written again") {
            val application = draft()
            application.on(UpdateDraftCommand("Jane", "Tan", born, "SG", 0L))

            application.on(UpdateDraftCommand("Jane", "Tan", born, "SG", 0L))

            application.firstName shouldBe FirstName("Jane")
            application.country shouldBe Country("SG")
        }

        context("a rejected field leaves the aggregate exactly as it was") {
            it("when the first name is invalid") {
                val application = draft()
                application.on(UpdateDraftCommand("Jane", "Tan", born, "SG", 0L))

                shouldThrow<InvalidNameException> { application.on(UpdateDraftCommand("  ", "Lim", born, "MY", 0L)) }
                    .message shouldBe "firstName must not be blank."

                application.firstName shouldBe FirstName("Jane")
                application.lastName shouldBe LastName("Tan")
            }

            it("when a later field is invalid, so the earlier valid ones are not half-applied") {
                val application = draft()
                application.on(UpdateDraftCommand("Jane", "Tan", born, "SG", 0L))

                // firstName and lastName are valid here; country is not.
                shouldThrow<InvalidCountryException> { application.on(UpdateDraftCommand("John", "Lim", born, "XX", 0L)) }

                application.firstName shouldBe FirstName("Jane")
                application.lastName shouldBe LastName("Tan")
                application.country shouldBe Country("SG")
            }

            it("when the date of birth is invalid") {
                val application = draft()

                shouldThrow<InvalidDateOfBirthException> {
                    application.on(UpdateDraftCommand("Jane", "Tan", LocalDate.now().plusDays(1), "SG", 0L))
                }.message shouldBe "dateOfBirth must be in the past."

                application.firstName.shouldBeNull()
            }
        }

        it("reports only the first invalid field, one DomainException per refusal") {
            val application = draft()

            // Both the name and the country are wrong; the name is reported.
            shouldThrow<InvalidNameException> { application.on(UpdateDraftCommand(null, "Tan", born, "XX", 0L)) }
                .message shouldBe "firstName is required."
        }

        // NotEditable can't be exercised yet: DRAFT is the only status until FR4 adds submit.
    }

    describe("the decision (FR8)") {

        fun checksComplete() = draft().apply {
            on(UpdateDraftCommand("Jane", "Tan", born, "SG", 0L))
            submit(true)
            startVerifying()
            completeChecks()
        }

        fun referred() = checksComplete().apply { refer(DecisionReason.FRAUD_SUSPECTED) }

        it("the system approves a completed application") {
            checksComplete().apply { approve() }.status shouldBe ApplicationStatus.APPROVED
        }

        it("the system refers with its own reason, and may not use a reviewer's") {
            referred().decisionReason shouldBe DecisionReason.FRAUD_SUSPECTED
            shouldThrow<IllegalArgumentException> { checksComplete().refer(DecisionReason.FRAUD_CONFIRMED) }
        }

        it("cannot decide twice") {
            val approved = checksComplete().apply { approve() }

            shouldThrow<NotEditableException> { approved.refer(DecisionReason.FRAUD_SUSPECTED) }
        }

        it("a reviewer approves a referred application, keeping why it was referred") {
            val application = referred().apply { approveOnReview() }

            application.status shouldBe ApplicationStatus.APPROVED
            application.decisionReason shouldBe DecisionReason.FRAUD_SUSPECTED
        }

        it("a reviewer declines with a reason of their own") {
            val application = referred().apply { declineOnReview(DecisionReason.FRAUD_CONFIRMED) }

            application.status shouldBe ApplicationStatus.DECLINED
            application.decisionReason shouldBe DecisionReason.FRAUD_CONFIRMED
        }

        it("a decline without a reason, or with the system's, is refused and changes nothing") {
            val application = referred()

            shouldThrow<InvalidDecisionReasonException> { application.declineOnReview(null) }
            shouldThrow<InvalidDecisionReasonException> { application.declineOnReview(DecisionReason.FRAUD_SUSPECTED) }

            application.status shouldBe ApplicationStatus.REFERRED
        }

        it("a reviewer can only decide a referred application") {
            shouldThrow<NotReferredException> { checksComplete().approveOnReview() }
            shouldThrow<NotReferredException> { checksComplete().apply { approve() }.declineOnReview(DecisionReason.POLICY) }
        }
    }
})
