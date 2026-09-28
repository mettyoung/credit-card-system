package com.mettyoung.creditcardapplication.vendor.internal

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SignatureVerifierTest : DescribeSpec({

    val token = "test-webhook-token"
    val verifier = SignatureVerifier(OnfidoProperties(
        "http://localhost", "v3.6", "api", token,
        Duration.ofSeconds(10), Duration.ofMinutes(30), Duration.ofMinutes(1), 5))

    fun sign(body: ByteArray, key: String = token): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    val body = """{"payload":{"resource_type":"check","action":"check.completed","object":{"id":"chk_1"}}}"""
        .toByteArray()

    describe("a genuine callback") {
        it("verifies against the raw bytes") {
            verifier.isValid(body, sign(body)) shouldBe true
        }

        it("verifies with an upper-case hex signature") {
            verifier.isValid(body, sign(body).uppercase()) shouldBe true
        }

        it("tolerates surrounding whitespace in the header") {
            verifier.isValid(body, "  ${sign(body)}  ") shouldBe true
        }
    }

    describe("anything else is rejected") {
        it("a signature made with the wrong token") {
            verifier.isValid(body, sign(body, "another-token")) shouldBe false
        }

        it("a body changed after signing, even by one byte") {
            val signature = sign(body)
            val tampered = """{"payload":{"resource_type":"check","action":"check.completed","object":{"id":"chk_2"}}}"""
                .toByteArray()

            verifier.isValid(tampered, signature) shouldBe false
        }

        it("re-serialised JSON, which is why the raw bytes are what gets verified") {
            // Same document, different key order. Parsing and re-serialising would break a genuine callback.
            val reordered = """{"payload":{"action":"check.completed","resource_type":"check","object":{"id":"chk_1"}}}"""
                .toByteArray()

            verifier.isValid(reordered, sign(body)) shouldBe false
        }

        it("an absent header") {
            verifier.isValid(body, null) shouldBe false
            verifier.isValid(body, "") shouldBe false
        }

        it("a header that is not hex at all") {
            verifier.isValid(body, "not-a-signature") shouldBe false
        }

        it("a truncated signature") {
            verifier.isValid(body, sign(body).take(32)) shouldBe false
        }

        it("an absent body") {
            verifier.isValid(null, sign(body)) shouldBe false
        }
    }
})
