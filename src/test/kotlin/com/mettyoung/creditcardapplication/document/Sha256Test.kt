package com.mettyoung.creditcardapplication.document

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class Sha256Test : DescribeSpec({

    val valid = "9a1f".repeat(16)

    describe("Sha256") {
        it("accepts 64 hex characters and lower-cases them") {
            Sha256(valid.uppercase()).value shouldBe valid
        }

        it("strips surrounding whitespace") {
            Sha256("  $valid  ").value shouldBe valid
        }

        withData(nameFn = { "rejects a digest of length ${it.length}" },
            "", "a".repeat(63), "a".repeat(65)) { raw ->
            shouldThrow<InvalidDocumentException> { Sha256(raw) }.message shouldBe
                "sha256 must be 64 hex characters."
        }

        it("rejects non-hex characters") {
            shouldThrow<InvalidDocumentException> { Sha256("z".repeat(64)) }.message shouldBe
                "sha256 must be hexadecimal."
        }

        it("rejects an absent digest as required") {
            shouldThrow<InvalidDocumentException> { Sha256(null) }.message shouldBe "sha256 is required."
        }

        it("compares in constant time, since one side is client-supplied") {
            Sha256(valid).matches(Sha256(valid)) shouldBe true
            Sha256(valid).matches(Sha256("b".repeat(64))) shouldBe false
            Sha256(valid).matches(null) shouldBe false
        }
    }
})
