package com.mettyoung.creditcardapplication.application.internal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class CountryTest : DescribeSpec({

    describe("Country") {

        context("an ISO 3166-1 alpha-2 code") {
            withData(
                nameFn = { "$it is accepted" },
                "SG", "MY", "GB", "US", "VN",
            ) { code -> Country(code).code shouldBe code }
        }

        context("case and whitespace") {
            withData(
                nameFn = { "'$it' normalises to SG" },
                "sg", "Sg", "  sg  ", "SG ",
            ) { raw -> Country(raw).code shouldBe "SG" }
        }

        context("anything that is not a current code") {
            withData(
                nameFn = { "'$it' is rejected" },
                "XX", "ZZ", "SGP", "S", "", "12", "AN",
            ) { raw ->
                shouldThrow<InvalidCountryException> { Country(raw) }.message shouldBe
                    "country must be an ISO 3166-1 alpha-2 code."
            }
        }

        context("a missing code") {
            it("is rejected as required") {
                shouldThrow<InvalidCountryException> { Country(null) }.message shouldBe "country is required."
            }
        }

        context("which field was rejected") {
            it("is named in the details") {
                shouldThrow<InvalidCountryException> { Country("XX") }.details() shouldBe
                    mapOf("country" to "country must be an ISO 3166-1 alpha-2 code.")
            }
        }
    }
})
