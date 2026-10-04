package com.mettyoung.creditcardapplication.vendor.internal

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.util.UUID

class VendorCheckTest : DescribeSpec({

    val now = Instant.parse("2026-09-27T10:00:00Z")
    val lease = Duration.ofMinutes(1)

    fun queued() = VendorCheck.queueIdv(UUID.randomUUID(), "onfido", UUID.randomUUID(), now)

    describe("queueIdv") {
        it("is due immediately and keyed so a retry cannot pay twice") {
            val applicationId = UUID.randomUUID()
            val documentId = UUID.randomUUID()

            val check = VendorCheck.queueIdv(applicationId, "onfido", documentId, now)

            check.status shouldBe CheckStatus.QUEUED
            check.attempts shouldBe 0
            check.nextAttemptAt shouldBe now
            check.idempotencyKey shouldBe IdempotencyKey("$applicationId:IDV:$documentId")
        }

        it("keys a re-upload as a new check, not a retry of the old one") {
            val applicationId = UUID.randomUUID()
            val first = VendorCheck.queueIdv(applicationId, "onfido", UUID.randomUUID(), now)
            val second = VendorCheck.queueIdv(applicationId, "onfido", UUID.randomUUID(), now)

            (first.idempotencyKey == second.idempotencyKey) shouldBe false
        }
    }

    describe("recordSubject") {
        it("keeps the first applicant, so a retry cannot orphan what the first one holds") {
            val check = queued().apply { claim(now, lease) }

            check.recordSubject(VendorSubjectRef("apl_first"))
            check.recordSubject(VendorSubjectRef("apl_second"))

            check.vendorSubjectRef shouldBe VendorSubjectRef("apl_first")
        }
    }

    describe("claim") {
        it("counts the attempt and takes a lease, so a crash looks like an expired lease") {
            val check = queued()

            check.claim(now, lease)

            check.status shouldBe CheckStatus.IN_PROGRESS
            check.attempts shouldBe 1
            check.leaseUntil shouldBe now.plus(lease)
        }
    }

    describe("awaitCallback") {
        it("records the vendor's handle and both timers") {
            val check = queued().apply { claim(now, lease) }

            check.awaitCallback(VendorRef("chk_1"), "{}", now, Duration.ofMinutes(30), Duration.ofMinutes(1))

            check.status shouldBe CheckStatus.AWAITING_CALLBACK
            check.isAwaitingCallback() shouldBe true
            check.vendorRef shouldBe VendorRef("chk_1")
            check.leaseUntil.shouldBeNull()
            check.nextAttemptAt shouldBe now.plusSeconds(60)
            check.isPastDeadline(now.plusSeconds(1799)) shouldBe false
            check.isPastDeadline(now.plusSeconds(1800)) shouldBe true
        }
    }

    describe("attempts") {
        withData(nameFn = { "after $it claims, attempts left against a max of 3 is ${it < 3}" }, 1, 2, 3, 4) {
                claims ->
            val check = queued()
            repeat(claims) { check.claim(now, lease) }

            check.hasAttemptsLeft(3) shouldBe (claims < 3)
        }
    }

    describe("a terminal check") {
        it("is COMPLETED when the vendor answered, whatever the answer was") {
            val check = queued().apply { claim(now, lease) }

            check.complete(IdvOutcome.FRAUD, """{"result":"consider"}""", now)

            check.status shouldBe CheckStatus.COMPLETED
            check.status.isTerminal() shouldBe true
            // A fraud verdict is an answer. Only an absent answer is a failure.
            check.outcome shouldBe IdvOutcome.FRAUD
            check.failureCode.shouldBeNull()
        }

        it("is FAILED only when no answer was obtained") {
            val check = queued().apply { claim(now, lease) }

            check.fail("deadline-exceeded", null, now)

            check.status shouldBe CheckStatus.FAILED
            check.status.isTerminal() shouldBe true
            check.failureCode shouldBe "deadline-exceeded"
        }
    }
})
