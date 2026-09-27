package com.mettyoung.creditcardapplication.application.internal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * Every declared-data converter routes a load back through its value object's constructor, so a row that
 * breaks the rules stops the read. One spec, because that contract is the same for all four.
 */
class DeclaredDataConverterTest : DescribeSpec({

    describe("FirstNameConverter") {
        val converter = FirstNameConverter()

        it("stores the plain value and rebuilds it") {
            converter.convertToDatabaseColumn(FirstName("Jane")) shouldBe "Jane"
            converter.convertToEntityAttribute("Jane") shouldBe FirstName("Jane")
        }

        it("keeps a value that was never set as null, both ways") {
            converter.convertToDatabaseColumn(null).shouldBeNull()
            converter.convertToEntityAttribute(null).shouldBeNull()
        }

        it("rejects a stored value that breaks the rules") {
            // Such a row can only come from a migration or a manual fix, never from the application.
            shouldThrow<InvalidNameException> { converter.convertToEntityAttribute("   ") }
        }
    }

    describe("LastNameConverter") {
        val converter = LastNameConverter()

        it("stores the plain value and rebuilds it") {
            converter.convertToDatabaseColumn(LastName("Tan")) shouldBe "Tan"
            converter.convertToEntityAttribute("Tan") shouldBe LastName("Tan")
        }

        it("keeps a value that was never set as null, both ways") {
            converter.convertToDatabaseColumn(null).shouldBeNull()
            converter.convertToEntityAttribute(null).shouldBeNull()
        }

        it("rejects a stored value that breaks the rules") {
            shouldThrow<InvalidNameException> { converter.convertToEntityAttribute("a".repeat(71)) }
        }
    }

    describe("DateOfBirthConverter") {
        val converter = DateOfBirthConverter()
        val born = LocalDate.of(1990, 4, 12)

        it("stores the plain date and rebuilds it") {
            converter.convertToDatabaseColumn(DateOfBirth(born)) shouldBe born
            converter.convertToEntityAttribute(born) shouldBe DateOfBirth(born)
        }

        it("keeps a date that was never set as null, both ways") {
            converter.convertToDatabaseColumn(null).shouldBeNull()
            converter.convertToEntityAttribute(null).shouldBeNull()
        }

        it("rejects a stored date outside the allowed range") {
            shouldThrow<InvalidDateOfBirthException> {
                converter.convertToEntityAttribute(LocalDate.of(1899, 12, 31))
            }
        }
    }

    describe("CountryConverter") {
        val converter = CountryConverter()

        it("stores the code and rebuilds it") {
            converter.convertToDatabaseColumn(Country("SG")) shouldBe "SG"
            converter.convertToEntityAttribute("SG") shouldBe Country("SG")
        }

        it("keeps a code that was never set as null, both ways") {
            converter.convertToDatabaseColumn(null).shouldBeNull()
            converter.convertToEntityAttribute(null).shouldBeNull()
        }

        it("rejects a stored code that is no longer ISO, rather than passing it on") {
            // ISO withdraws codes. Failing the read is the converter contract working, not a bug.
            shouldThrow<InvalidCountryException> { converter.convertToEntityAttribute("AN") }
        }
    }
})
