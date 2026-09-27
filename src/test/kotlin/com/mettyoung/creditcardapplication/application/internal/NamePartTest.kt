package com.mettyoung.creditcardapplication.application.internal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

/**
 * FirstName and LastName share NameRules, so they share a spec: every case runs against both, which is what
 * stops one drifting from the other.
 */
class NamePartTest : DescribeSpec({

    // field name -> build from a raw string, read the value back
    val parts = mapOf<String, (String?) -> String>(
        "firstName" to { raw -> FirstName(raw).value },
        "lastName" to { raw -> LastName(raw).value },
    )

    parts.forEach { (field, build) ->

        describe(field) {

            context("a name in any script") {
                withData(
                    nameFn = { "$it is kept as typed" },
                    "Jane", "José", "李", "O'Brien-Smith", "Nguyễn",
                ) { raw -> build(raw) shouldBe raw }
            }

            context("whitespace") {
                it("is stripped from both ends") {
                    build("  Jane  ") shouldBe "Jane"
                }

                withData(
                    nameFn = { "a value of ${it.length} whitespace characters is rejected as blank" },
                    "", "   ", "\t\n", " ",
                ) { raw ->
                    shouldThrow<InvalidNameException> { build(raw) }.message shouldBe "$field must not be blank."
                }
            }

            context("a missing name") {
                it("is rejected as required") {
                    shouldThrow<InvalidNameException> { build(null) }.message shouldBe "$field is required."
                }
            }

            context("control characters") {
                withData(
                    nameFn = { "U+%04X is rejected".format(it.code) },
                    '\u0000', '\u0007', '\n', '\u001b',
                ) { control ->
                    shouldThrow<InvalidNameException> { build("Jane${control}Tan") }.message shouldBe
                        "$field must not contain control characters."
                }
            }

            context("the 70 character limit the column enforces") {
                it("accepts a name of exactly 70") {
                    build("a".repeat(70)).length shouldBe 70
                }

                it("rejects one character more") {
                    shouldThrow<InvalidNameException> { build("a".repeat(71)) }.message shouldBe
                        "$field must be at most 70 characters."
                }

                it("counts code points like Postgres, so 70 emoji fit") {
                    // Each emoji is 2 UTF-16 chars but 1 code point (1 Postgres character).
                    val seventyCodePoints = "😀".repeat(70)

                    build(seventyCodePoints) shouldBe seventyCodePoints
                }
            }

            context("which field was rejected") {
                it("is carried on the exception, so the problem response names it") {
                    shouldThrow<InvalidNameException> { build("  ") }.details() shouldBe
                        mapOf(field to "$field must not be blank.")
                }
            }
        }
    }

    describe("the two parts") {
        it("are separate types, so a long surname is not rejected for a long given name") {
            // 70 each rather than 100 shared.
            FirstName("a".repeat(70)).value.length shouldBe 70
            LastName("b".repeat(70)).value.length shouldBe 70
        }
    }
})
