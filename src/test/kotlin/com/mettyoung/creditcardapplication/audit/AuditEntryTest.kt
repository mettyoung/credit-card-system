package com.mettyoung.creditcardapplication.audit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * The parameter object carries every rule that used to be a caller's responsibility, so its own guarantees are
 * worth pinning: an entry cannot be half-formed, and cannot be changed after the fact by whoever built it.
 */
class AuditEntryTest : DescribeSpec({

    val applicationId: UUID = UUID.randomUUID()

    describe("who caused the event") {

        it("records an applicant with the id that acted") {
            val entry = AuditEntry.byApplicant(applicationId, AuditEventType.UPLOAD_REQUESTED, "u_1", mapOf())

            entry.actor() shouldBe Actor.APPLICANT
            entry.actorId() shouldBe "u_1"
        }

        it("records the system with no actor id, since there is nobody to name") {
            val entry = AuditEntry.bySystem(applicationId, AuditEventType.STATUS_CHANGED, mapOf())

            entry.actor() shouldBe Actor.SYSTEM
            entry.actorId() shouldBe null
        }
    }

    describe("an entry that could not be read back") {

        it("refuses one with no application to attach it to") {
            shouldThrow<NullPointerException> {
                AuditEntry.bySystem(null, AuditEventType.STATUS_CHANGED, mapOf())
            }
        }

        it("refuses one with no type, which is the whole fact it records") {
            shouldThrow<NullPointerException> { AuditEntry.bySystem(applicationId, null, mapOf()) }
        }
    }

    describe("the payload") {

        it("treats an absent one as empty rather than failing at the column") {
            // payload is NOT NULL in the schema, so a null here would surface as a constraint violation far
            // from the caller that caused it.
            AuditEntry.bySystem(applicationId, AuditEventType.STATUS_CHANGED, null).payload() shouldBe mapOf()
        }

        it("cannot be changed by the caller after the entry is built") {
            // The point of an append-only log is that a recorded fact stays recorded. Holding the caller's
            // map would let it be edited between construction and the write.
            val mutable = mutableMapOf<String, Any>("documentId" to "d_1")

            val entry = AuditEntry.bySystem(applicationId, AuditEventType.DOCUMENT_VERIFIED, mutable)
            mutable["documentId"] = "d_2"
            mutable["leaked"] = "Jane"

            entry.payload() shouldContainExactly mapOf("documentId" to "d_1")
        }
    }
})
