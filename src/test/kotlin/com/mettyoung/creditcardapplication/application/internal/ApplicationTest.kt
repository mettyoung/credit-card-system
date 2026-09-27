package com.mettyoung.creditcardapplication.application.internal

import com.mettyoung.creditcardapplication.application.UpdateDraftCommand
import com.mettyoung.creditcardapplication.application.ApplicationStatus
import com.mettyoung.creditcardapplication.application.CardProduct
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
})
