package com.mettyoung.creditcardapplication.document

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import com.mettyoung.creditcardapplication.shared.DomainException
import tools.jackson.databind.exc.ValueInstantiationException
import tools.jackson.databind.json.JsonMapper

/**
 * Binding the body *is* the validation: every field is a value object that validates in its own constructor,
 * so there is no state in which an RequestUploadCommand exists holding something nobody has checked.
 *
 * A real mapper rather than a hand-built record, because the thing worth asserting is what Jackson does —
 * that a bare JSON string becomes the value object, and that the value object's own refusal is what comes
 * back out when it cannot.
 */
private data class Rejected(val case: String, val field: String, val body: String)

class RequestUploadCommandTest : DescribeSpec({

    val json = JsonMapper.builder().build()

    val digest = "a".repeat(64)

    fun body(kind: String = "ID", contentType: String = "image/jpeg", sizeBytes: Long = 1024,
             sha256: String = digest) =
        """{"kind":"$kind","contentType":"$contentType","sizeBytes":$sizeBytes,"sha256":"$sha256"}"""

    fun read(raw: String) = json.readValue(raw, RequestUploadCommand::class.java)

    describe("binding a request body") {

        it("turns each declared string into the value object that owns its rule") {
            val request = read(body())

            request.kind() shouldBe DocumentKind.ID
            request.contentType() shouldBe ContentType.JPEG
            request.sha256() shouldBe Sha256(digest)
            request.sizeBytes() shouldBe 1024
        }

        it("accepts a content type by its media type, not by the enum constant") {
            // The client sends "application/pdf"; PDF is our name for it and no concern of theirs.
            read(body(contentType = "application/pdf")).contentType() shouldBe ContentType.PDF
        }

        it("normalises the way the value objects do, so the wire is forgiving and the model is not") {
            val request = read(body(kind = " id ", contentType = "IMAGE/JPEG", sha256 = digest.uppercase()))

            request.kind() shouldBe DocumentKind.ID
            request.contentType() shouldBe ContentType.JPEG
            request.sha256().value() shouldBe digest
        }
    }

    describe("a value the model will not accept") {

        // Jackson wraps whatever a creator throws, so the domain's refusal arrives as the cause rather than
        // at the surface. That is exactly why ApiExceptionHandler walks the cause chain: unwrapped, the
        // client would get a generic "malformed body" instead of the field and the rule.
        withData(
            nameFn = { "refuses ${it.field} - ${it.case}" },
            Rejected("an unknown kind", "kind", body(kind = "PASSPORT")),
            Rejected("a content type off the allow-list", "contentType", body(contentType = "image/gif")),
            Rejected("a digest of the wrong length", "sha256", body(sha256 = "abc")),
            Rejected("a digest that is not hexadecimal", "sha256", body(sha256 = "z".repeat(64))),
        ) { (_, field, raw) ->
            val thrown = shouldThrow<ValueInstantiationException> { read(raw) }

            val refusal = generateSequence(thrown.cause) { it.cause }
                .filterIsInstance<InvalidDocumentException>()
                .first()

            // The field is what the problem response turns into errors[].field, so it is the part a client
            // actually acts on.
            refusal.details().keys shouldBe setOf(field)
            refusal.category shouldBe DomainException.Category.INVALID_VALUE
        }
    }
})
