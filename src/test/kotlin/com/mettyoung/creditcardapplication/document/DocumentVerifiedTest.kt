package com.mettyoung.creditcardapplication.document

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The verdict is both the module's return value and the response body, so what reaches the wire is a property
 * of this record rather than of a separate DTO. That makes it worth asserting: a field added here, or a
 * derived accessor, becomes public API by default.
 */
class DocumentVerifiedTest : DescribeSpec({

    val json = JsonMapper.builder().build()

    val documentId = UUID.randomUUID()
    val applicationId = UUID.randomUUID()

    @Suppress("UNCHECKED_CAST")
    fun wire(verdict: DocumentVerified): Map<String, Any?> =
        json.readValue(json.writeValueAsString(verdict), Map::class.java) as Map<String, Any?>

    describe("serialising a verdict") {

        it("carries exactly the four fields a client needs") {
            val verdict = DocumentVerified(documentId, applicationId, DocumentKind.ID,
                DocumentStatus.INVALID, "content-type-mismatch")

            wire(verdict) shouldContainExactly mapOf<String, Any?>(
                "documentId" to documentId.toString(),
                "kind" to "ID",
                "status" to "INVALID",
                "reason" to "content-type-mismatch",
            )
        }

        it("omits the application id, which is already in the path") {
            wire(DocumentVerified(documentId, applicationId, DocumentKind.ID, DocumentStatus.UPLOADED, null))
                .containsKey("applicationId") shouldBe false
        }

        it("does not leak isUploaded as a field") {
            // A public boolean accessor would otherwise show up as "uploaded" and become API by accident.
            wire(DocumentVerified(documentId, applicationId, DocumentKind.ID, DocumentStatus.UPLOADED, null))
                .containsKey("uploaded") shouldBe false
        }
    }
})
