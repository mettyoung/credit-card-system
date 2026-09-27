package com.mettyoung.creditcardapplication.application.internal

import com.mettyoung.creditcardapplication.application.ApplicationStatus
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * The codes are the API's problem types: renaming an exception changes them, which these tests make visible.
 *
 * In this module rather than beside DomainException, because every exception it names is this module's and
 * they are no longer visible from outside it — which is the point. DomainException's own contract is what is
 * being checked; these are simply the subjects.
 */
class DomainExceptionTest : DescribeSpec({

    describe("code") {

        it("is the class name without its Exception suffix, for a conflict") {
            DraftAlreadyExistsException(UUID.randomUUID()).code() shouldBe "draft-already-exists"
            NotEditableException(ApplicationStatus.DRAFT).code() shouldBe "not-editable"
            VersionMismatchException(1).code() shouldBe "version-mismatch"
        }

        it("is fixed by the category for a not-found, so no response hints at what exists") {
            ApplicationNotFoundException().code() shouldBe "not-found"
        }

        it("is fixed by the category for an invalid value, which is a malformed request like any other") {
            // Every invalid value answers the same type, whatever its class, so the field-specific
            // classes never leak a name to the client.
            InvalidNameException("firstName", "firstName is required.").code() shouldBe "invalid-request"
            InvalidDateOfBirthException("dateOfBirth is required.").code() shouldBe "invalid-request"
            InvalidCountryException("country is required.").code() shouldBe "invalid-request"
        }
    }

    describe("detail") {

        it("is the exception message") {
            ApplicationNotFoundException().message shouldBe "Application not found."
            InvalidNameException("firstName", "firstName must not be blank.").message shouldBe
                "firstName must not be blank."
        }
    }

    describe("details") {

        it("carries the facts the response adds") {
            val existing = UUID.randomUUID()

            DraftAlreadyExistsException(existing).details() shouldBe mapOf("applicationId" to existing)
            VersionMismatchException(3).details() shouldBe mapOf("currentVersion" to 3L)
            NotEditableException(ApplicationStatus.DRAFT).details() shouldBe
                mapOf("currentStatus" to ApplicationStatus.DRAFT)
        }

        it("keys an invalid value by the rejected field, which the advice turns into errors[]") {
            InvalidNameException("firstName", "firstName is required.").details() shouldBe
                mapOf("firstName" to "firstName is required.")
            InvalidNameException("lastName", "lastName is required.").details() shouldBe
                mapOf("lastName" to "lastName is required.")
            InvalidDateOfBirthException("dateOfBirth is required.").details() shouldBe
                mapOf("dateOfBirth" to "dateOfBirth is required.")
            InvalidCountryException("country is required.").details() shouldBe
                mapOf("country" to "country is required.")
        }

        it("is empty for a not-found") {
            ApplicationNotFoundException().details().shouldBeEmpty()
        }

        it("never shadows an RFC 9457 member") {
            listOf(
                DraftAlreadyExistsException(UUID.randomUUID()),
                NotEditableException(ApplicationStatus.DRAFT),
                VersionMismatchException(1),
                InvalidNameException("firstName", "firstName is required."),
                InvalidNameException("lastName", "lastName is required."),
                InvalidDateOfBirthException("dateOfBirth is required."),
                InvalidCountryException("country is required."),
                ApplicationNotFoundException(),
            ).forEach { exception ->
                listOf("type", "title", "status", "detail", "instance").forEach { reserved ->
                    exception.details() shouldNotContainKey reserved
                }
            }
        }
    }
})
