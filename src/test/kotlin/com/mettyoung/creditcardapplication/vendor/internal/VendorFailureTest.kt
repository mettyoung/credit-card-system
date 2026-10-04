package com.mettyoung.creditcardapplication.vendor.internal

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class VendorFailureTest : DescribeSpec({

    describe("retryable") {
        // The sealed hierarchy is what forces this decision for every new failure kind: retrying an
        // InvalidRequest sends the same wrong request, and retrying SubjectNotFound cannot conjure a record.
        withData(
            nameFn = { "${it.first::class.simpleName} is retryable=${it.second}" },
            VendorFailure.Timeout() to true,
            VendorFailure.Unavailable(503) to true,
            VendorFailure.Unavailable(429) to true,
            VendorFailure.SubjectNotFound() to false,
            VendorFailure.InvalidRequest("bad") to false,
        ) { (failure, retryable) -> failure.retryable() shouldBe retryable }
    }

    describe("code") {
        it("is a stable string for the audit log, carrying the status where there is one") {
            VendorFailure.Timeout().code() shouldBe "timeout"
            VendorFailure.Unavailable(503).code() shouldBe "unavailable-503"
            VendorFailure.SubjectNotFound().code() shouldBe "subject-not-found"
            VendorFailure.InvalidRequest("whatever").code() shouldBe "invalid-request"
        }

        it("never carries the vendor's own message, which could hold anything") {
            VendorFailure.InvalidRequest("applicant Jane Tan not found").code() shouldBe "invalid-request"
        }
    }
})
