package com.mettyoung.creditcardapplication.document.internal

import com.mettyoung.creditcardapplication.document.InvalidDocumentException
import com.mettyoung.creditcardapplication.document.Sha256
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Both converters route a load back through their value object's constructor, so a row that breaks the rules
 * stops the read instead of becoming an object nothing checked.
 *
 * Worth its own spec because the integration specs only ever round-trip rows this application wrote, and
 * those are valid by construction — a converter that quietly returned the raw column would pass every one of
 * them. The rows this protects against come from a migration or a manual fix.
 */
class DocumentConverterTest : DescribeSpec({

    describe("ObjectKeyConverter") {
        val converter = ObjectKeyConverter()
        val key = ObjectKey.forDocument(UUID.randomUUID(), UUID.randomUUID())

        it("stores the plain value and rebuilds it") {
            converter.convertToDatabaseColumn(key) shouldBe key.value()
            converter.convertToEntityAttribute(key.value()) shouldBe key
        }

        it("keeps a value that was never set as null, both ways") {
            converter.convertToDatabaseColumn(null).shouldBeNull()
            converter.convertToEntityAttribute(null).shouldBeNull()
        }

        withData(nameFn = { "rejects a stored value that is $it" }, "blank", "empty") { stored ->
            shouldThrow<IllegalArgumentException> {
                converter.convertToEntityAttribute(if (stored == "blank") "   " else "")
            }
        }
    }

    describe("Sha256Converter") {
        val converter = Sha256Converter()
        val digest = Sha256("a".repeat(64))

        it("stores the plain value and rebuilds it") {
            converter.convertToDatabaseColumn(digest) shouldBe digest.value()
            converter.convertToEntityAttribute(digest.value()) shouldBe digest
        }

        it("keeps a value that was never set as null, both ways") {
            converter.convertToDatabaseColumn(null).shouldBeNull()
            converter.convertToEntityAttribute(null).shouldBeNull()
        }

        withData(
            nameFn = { "rejects a stored digest that is ${it.first}" },
            "too short" to "abc",
            "not hexadecimal" to "z".repeat(64),
        ) { (_, stored) ->
            shouldThrow<InvalidDocumentException> { converter.convertToEntityAttribute(stored) }
        }

        it("normalises a stored digest the same way a new one is, so a comparison cannot miss") {
            // Sha256 lower-cases in its constructor. If the column held upper case - from a migration, say -
            // the loaded value still matches one computed from the bytes.
            converter.convertToEntityAttribute("A".repeat(64)) shouldBe Sha256("a".repeat(64))
        }
    }

    describe("the key itself") {

        it("is derived from ids, never from anything a client sent") {
            // A client-supplied name could steer a write at another application's prefix, or escape the
            // bucket with ../ - so the key is built, not accepted.
            val applicationId = UUID.randomUUID()
            val documentId = UUID.randomUUID()

            ObjectKey.forDocument(applicationId, documentId).value() shouldBe
                "applications/$applicationId/documents/$documentId"
        }
    }
})
