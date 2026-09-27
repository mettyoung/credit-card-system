package com.mettyoung.creditcardapplication.application.internal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import java.time.LocalDate

class DateOfBirthTest : DescribeSpec({

    describe("DateOfBirth") {

        context("a date in the past") {
            withData(
                nameFn = { "$it is accepted" },
                LocalDate.of(1990, 4, 12), LocalDate.of(1900, 1, 1), LocalDate.now().minusDays(1),
            ) { date -> DateOfBirth(date).value shouldBe date }
        }

        context("a date that is not in the past") {
            withData(
                nameFn = { "$it is rejected" },
                LocalDate.now(), LocalDate.now().plusDays(1), LocalDate.of(2999, 12, 31),
            ) { date ->
                shouldThrow<InvalidDateOfBirthException> { DateOfBirth(date) }.message shouldBe
                    "dateOfBirth must be in the past."
            }
        }

        context("the 1900 floor") {
            it("rejects the day before it") {
                shouldThrow<InvalidDateOfBirthException> { DateOfBirth(LocalDate.of(1899, 12, 31)) }
                    .message shouldBe "dateOfBirth must not be before 1900-01-01."
            }

            it("is absolute, not relative, so a stored row never ages out of range") {
                // A "within the last 120 years" rule would make an applicant's own row fail to load
                // once they passed 120. An absolute floor cannot.
                DateOfBirth(DateOfBirth.FLOOR).value shouldBe LocalDate.of(1900, 1, 1)
            }
        }

        context("a missing date") {
            it("is rejected as required") {
                shouldThrow<InvalidDateOfBirthException> { DateOfBirth(null) }.message shouldBe
                    "dateOfBirth is required."
            }
        }

        context("eligibility") {
            it("is not checked here: a minor is a valid date of birth, and a policy decision later") {
                val fifteenYearOld = LocalDate.now().minusYears(15)

                DateOfBirth(fifteenYearOld).value shouldBe fifteenYearOld
            }
        }

        context("which field was rejected") {
            it("is named in the details") {
                shouldThrow<InvalidDateOfBirthException> { DateOfBirth(null) }.details() shouldBe
                    mapOf("dateOfBirth" to "dateOfBirth is required.")
            }
        }
    }
})
