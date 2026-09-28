package com.mettyoung.creditcardapplication.document

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class ContentTypeTest : DescribeSpec({

    describe("of") {
        withData(
            nameFn = { "accepts ${it.first}" },
            "image/jpeg" to ContentType.JPEG,
            "image/png" to ContentType.PNG,
            "application/pdf" to ContentType.PDF,
            "IMAGE/JPEG" to ContentType.JPEG,
            "  image/png  " to ContentType.PNG,
        ) { (raw, expected) -> ContentType.of(raw) shouldBe expected }

        withData(nameFn = { "rejects '$it'" }, "image/gif", "text/plain", "", "application/x-pdf") { raw ->
            shouldThrow<InvalidDocumentException> { ContentType.of(raw) }
                .details() shouldBe mapOf("contentType" to
                    "contentType must be one of image/jpeg, image/png, application/pdf.")
        }

        it("rejects an absent type as required") {
            shouldThrow<InvalidDocumentException> { ContentType.of(null) }.message shouldBe
                "contentType is required."
        }
    }

    describe("magic bytes") {
        // A declared content type is a claim. These are the evidence, which is why /complete reads the
        // first bytes instead of trusting the header.
        it("recognise a real JPEG header") {
            ContentType.JPEG.matchesMagicBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00))
                .shouldBe(true)
        }

        it("recognise a real PNG header") {
            val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
                0x0D, 0x0A, 0x1A, 0x0A)

            ContentType.PNG.matchesMagicBytes(png) shouldBe true
        }

        it("recognise a real PDF header") {
            ContentType.PDF.matchesMagicBytes("%PDF-1.7".toByteArray()) shouldBe true
        }

        it("catch a PDF renamed as a JPEG, which is the whole point") {
            ContentType.JPEG.matchesMagicBytes("%PDF-1.7".toByteArray()) shouldBe false
        }

        it("reject a head too short to decide on") {
            ContentType.PNG.matchesMagicBytes(byteArrayOf(0x89.toByte())) shouldBe false
            ContentType.PNG.matchesMagicBytes(null) shouldBe false
        }

        it("read enough bytes for the longest signature") {
            ContentType.magicBytesToRead() shouldBe 8
        }
    }
})
