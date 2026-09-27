package com.mettyoung.creditcardapplication.shared

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.ints.shouldBePositive
import io.kotest.matchers.shouldBe

class UuidV7Test : DescribeSpec({

    describe("UuidV7.generate") {

        it("produces a version 7 uuid with the RFC variant") {
            val uuid = UuidV7.generate()

            uuid.version() shouldBe 7
            uuid.variant() shouldBe 2
        }

        it("embeds the time it was generated") {
            val before = System.currentTimeMillis()
            val embedded = UuidV7.generate().mostSignificantBits ushr 16
            val after = System.currentTimeMillis()

            embedded shouldBeGreaterThanOrEqualTo before
            embedded shouldBeLessThanOrEqualTo after
        }

        it("orders ids from later milliseconds after earlier ones") {
            val first = UuidV7.generate()
            Thread.sleep(2)
            val second = UuidV7.generate()

            // Postgres compares uuid bytes unsigned; the timestamp lives in the most significant bits.
            java.lang.Long.compareUnsigned(second.mostSignificantBits, first.mostSignificantBits).shouldBePositive()
        }
    }
})
