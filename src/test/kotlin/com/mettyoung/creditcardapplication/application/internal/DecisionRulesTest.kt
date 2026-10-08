package com.mettyoung.creditcardapplication.application.internal

import com.mettyoung.creditcardapplication.application.DecisionReason
import com.mettyoung.creditcardapplication.application.RequirementStatus
import com.mettyoung.creditcardapplication.application.RequirementType
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class DecisionRulesTest : DescribeSpec({

    fun identity(status: RequirementStatus, outcome: String?) =
        DecisionRules.Evidence(RequirementType.IDENTITY, status, outcome)

    fun decide(vararg evidence: DecisionRules.Evidence) = DecisionRules.decide(evidence.toList())

    describe("the decision table") {

        it("approves a verified identity") {
            decide(identity(RequirementStatus.RECEIVED, "VERIFIED"))
                .shouldBeInstanceOf<DecisionRules.Decision.Approve>()
        }

        it("refers suspected fraud - an answer, but one for a person to judge") {
            decide(identity(RequirementStatus.RECEIVED, "FRAUD"))
                .shouldBeInstanceOf<DecisionRules.Decision.Refer>().reason() shouldBe DecisionReason.FRAUD_SUSPECTED
        }

        it("refers an unavailable identity - an outage is not a decline") {
            decide(identity(RequirementStatus.UNAVAILABLE, null))
                .shouldBeInstanceOf<DecisionRules.Decision.Refer>().reason() shouldBe DecisionReason.EVIDENCE_UNAVAILABLE
        }

        it("refers when there is nothing to approve on, rather than reading it as nothing wrong") {
            decide().shouldBeInstanceOf<DecisionRules.Decision.Refer>()
        }
    }

    describe("never declines") {

        // FR8.1: only a person declines. Whatever the evidence, the system's answer is approve or refer.
        withData(
            nameFn = { "for ${it.status} / ${it.outcome}" },
            identity(RequirementStatus.RECEIVED, "VERIFIED"),
            identity(RequirementStatus.RECEIVED, "FRAUD"),
            identity(RequirementStatus.RECEIVED, "UNREADABLE"),
            identity(RequirementStatus.UNAVAILABLE, null),
            identity(RequirementStatus.PENDING, null),
            identity(RequirementStatus.NEEDS_EVIDENCE, null),
        ) { evidence ->
            val decision = decide(evidence)
            (decision is DecisionRules.Decision.Approve || decision is DecisionRules.Decision.Refer) shouldBe true
            if (decision is DecisionRules.Decision.Refer) {
                decision.reason().isReviewerReason shouldBe false
            }
        }
    }
})
