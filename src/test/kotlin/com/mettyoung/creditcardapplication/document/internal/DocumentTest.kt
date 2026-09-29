package com.mettyoung.creditcardapplication.document.internal

import com.mettyoung.creditcardapplication.document.ContentType
import com.mettyoung.creditcardapplication.document.DocumentAlreadySettledException
import com.mettyoung.creditcardapplication.document.InvalidDocumentException
import com.mettyoung.creditcardapplication.document.Sha256
import com.mettyoung.creditcardapplication.document.DocumentKind
import com.mettyoung.creditcardapplication.document.RequestUploadCommand
import com.mettyoung.creditcardapplication.document.DocumentStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class DocumentTest : DescribeSpec({

    val now = Instant.parse("2026-09-27T10:00:00Z")
    val digest = Sha256("a".repeat(64))

    fun owner(applicationId: UUID = UUID.randomUUID()) = Document.Owner(applicationId, "u_1")

    fun declared(sizeBytes: Long = 1024) =
        RequestUploadCommand(DocumentKind.ID, ContentType.JPEG, sizeBytes, digest)

    fun request(sizeBytes: Long = 1024) = Document.requestUpload(owner(), declared(sizeBytes), now)

    describe("requestUpload") {

        it("starts PENDING_UPLOAD with a key derived from ids, never from client input") {
            val applicationId = UUID.randomUUID()
            val document = Document.requestUpload(owner(applicationId), declared(), now)

            document.status shouldBe DocumentStatus.PENDING_UPLOAD
            document.objectKey.value shouldBe
                "applications/$applicationId/documents/${document.id}"
            document.isSendable() shouldBe false
        }

        context("the size the column also enforces") {
            it("accepts exactly 10 MB") {
                request(sizeBytes = 10 * 1024 * 1024).sizeBytes shouldBe 10 * 1024 * 1024
            }

            withData(nameFn = { "rejects $it bytes" }, 0L, -1L, 10L * 1024 * 1024 + 1) { size ->
                shouldThrow<InvalidDocumentException> { request(sizeBytes = size) }
            }
        }
    }

    describe("the verdict") {

        it("only a verified object may be sent to a vendor") {
            val document = request()

            document.markUploaded()

            document.isSendable() shouldBe true
        }

        it("an invalid object is never sendable") {
            val document = request()

            document.markInvalid("content-type-mismatch")

            document.status shouldBe DocumentStatus.INVALID
            document.isSendable() shouldBe false
        }

        it("is idempotent, so replaying /complete re-asserts the same answer") {
            val document = request()
            document.markUploaded()

            document.markUploaded()

            document.status shouldBe DocumentStatus.UPLOADED
        }

        it("refuses to flip a verdict that was already reached the other way") {
            val document = request()
            document.markUploaded()

            // Not UploadIncomplete: an object did arrive, and saying otherwise would contradict the
            // currentStatus the same response reports.
            val refusal = shouldThrow<DocumentAlreadySettledException> { document.markInvalid("content-type-mismatch") }

            refusal.currentStatus() shouldBe DocumentStatus.UPLOADED
            refusal.details() shouldBe mapOf("currentStatus" to DocumentStatus.UPLOADED)
        }

        it("cannot expire a document that already has a verdict") {
            val document = request()
            document.markUploaded()

            shouldThrow<DocumentAlreadySettledException> { document.markExpired() }
        }

        it("still reports an absent object as an incomplete upload, which is the other rule") {
            // The two are opposites and must stay separate: nothing arrived, versus something arrived and was
            // already judged.
            val document = request()

            document.markExpired()

            document.status shouldBe DocumentStatus.EXPIRED
        }
    }
})
