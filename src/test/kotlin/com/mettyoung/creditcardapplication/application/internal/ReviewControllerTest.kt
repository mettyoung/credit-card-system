package com.mettyoung.creditcardapplication.application.internal

import com.mettyoung.creditcardapplication.application.ApplicationStatus
import com.mettyoung.creditcardapplication.application.CardProduct
import com.mettyoung.creditcardapplication.application.DecisionReason
import com.mettyoung.creditcardapplication.application.UpdateDraftCommand
import com.mettyoung.creditcardapplication.support.DockerAvailable
import com.mettyoung.creditcardapplication.support.dockerIsAvailable
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * FR8.2 / FR8.3: the reviewer's side, over HTTP. A referred application is built through the aggregate's own
 * transitions and saved, rather than driven through a vendor: what is under test is the review, and the path to
 * REFERRED is IdentityVerificationTest's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.workers.enabled=false"])
@EnabledIf(DockerAvailable::class)
class ReviewControllerTest : BehaviorSpec() {

    // Real time: the review queue compares it with the application clock to decide what is overdue.
    private val at = Instant.now()

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        init {
            if (dockerIsAvailable()) {
                postgres.start()
            }
        }
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var repository: ApplicationRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val user = "u_" + UUID.randomUUID()
    private val reviewer = "r_" + UUID.randomUUID()

    init {
        extension(SpringExtension())

        fun checksComplete(): Application = Application.createDraft(user, CardProduct.CLASSIC).apply {
            on(UpdateDraftCommand("Jane", "Tan", LocalDate.of(1990, 4, 12), "SG", 0L))
            submit(true, at)
            startVerifying(at)
            completeChecks(at)
        }

        fun saved(application: Application): Application = repository.saveAndFlush(application)

        fun referred(): Application = saved(checksComplete().apply { refer(DecisionReason.FRAUD_SUSPECTED, at) })

        fun decide(id: UUID, body: String, reviewerId: String? = reviewer) =
            mvc.perform(post("/v1/review/applications/$id/decision")
                .apply { if (reviewerId != null) header("X-Reviewer-Id", reviewerId) }
                .contentType(MediaType.APPLICATION_JSON).content(body))

        fun statusOf(id: UUID): ApplicationStatus = repository.findById(id).orElseThrow().status

        Given("a referred application") {
            val application = referred()
            val id = application.id
            val version = application.version

            Then("the review queue lists it with why it was referred, and not an approved one") {
                val approved = saved(checksComplete().apply { approve(at) })

                val body = mvc.perform(get("/v1/review/applications").header("X-Reviewer-Id", reviewer))
                    .andExpect(status().isOk).andReturn().response.contentAsString

                body.contains(""""id":"$id"""") shouldBe true
                body.contains(""""decisionReason":"FRAUD_SUSPECTED"""") shouldBe true
                body.contains(approved.id.toString()) shouldBe false
            }

            Then("a reviewer approves it, and who decided is in the audit log") {
                decide(id, """{"outcome":"APPROVED","version":$version}""")
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.status").value("APPROVED"))

                statusOf(id) shouldBe ApplicationStatus.APPROVED
                jdbc.queryForObject("SELECT actor || ':' || actor_id FROM audit_event " +
                    "WHERE application_id = ? AND type = 'DECISION_MADE'", String::class.java, id) shouldBe
                    "REVIEWER:$reviewer"
            }

            Then("a reviewer declines it with a reason of their own") {
                decide(id, """{"outcome":"DECLINED","reason":"FRAUD_CONFIRMED","version":$version}""")
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.status").value("DECLINED"))

                repository.findById(id).orElseThrow().decisionReason shouldBe DecisionReason.FRAUD_CONFIRMED
            }

            Then("a decline without a reason is refused, and it stays referred") {
                decide(id, """{"outcome":"DECLINED","version":$version}""")
                    .andExpect(status().isUnprocessableContent)

                statusOf(id) shouldBe ApplicationStatus.REFERRED
            }

            Then("a decline with a reason only the system sets is refused") {
                decide(id, """{"outcome":"DECLINED","reason":"FRAUD_SUSPECTED","version":$version}""")
                    .andExpect(status().isUnprocessableContent)
            }

            Then("a stale copy is a conflict") {
                decide(id, """{"outcome":"APPROVED","version":${version + 1}}""")
                    .andExpect(status().isConflict)
                    .andExpect(jsonPath("$.type").value("/problems/version-mismatch"))
            }

            Then("no reviewer header is 401, as for an applicant") {
                decide(id, """{"outcome":"APPROVED","version":$version}""", reviewerId = null)
                    .andExpect(status().isUnauthorized)
            }

            Then("the applicant sees the outcome but never the reason") {
                decide(id, """{"outcome":"DECLINED","reason":"FRAUD_CONFIRMED","version":$version}""")

                mvc.perform(get("/v1/applications/$id").header("X-User-Id", user))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.status").value("DECLINED"))
                    .andExpect(jsonPath("$.decisionReason").doesNotExist())
            }
        }

        Given("one referral waiting past its deadline and one fresh (FR9.2)") {
            val old = referred()
            jdbc.update("UPDATE application SET status_changed_at = now() - interval '3 days' WHERE id = ?", old.id)
            val fresh = referred()

            Then("the old one is flagged overdue and listed first; the fresh one is not; neither is closed") {
                val body = mvc.perform(get("/v1/review/applications").header("X-Reviewer-Id", reviewer))
                    .andExpect(status().isOk).andReturn().response.contentAsString

                val oldAt = body.indexOf(""""id":"${old.id}"""")
                val freshAt = body.indexOf(""""id":"${fresh.id}"""")
                (oldAt in 0 until freshAt) shouldBe true
                Regex(""""id":"${old.id}"[^}]*"overdue":true""").containsMatchIn(body) shouldBe true
                Regex(""""id":"${fresh.id}"[^}]*"overdue":false""").containsMatchIn(body) shouldBe true
                statusOf(old.id) shouldBe ApplicationStatus.REFERRED
            }
        }

        Given("an application the system did not refer") {
            val approved = saved(checksComplete().apply { approve(at) })

            Then("a reviewer cannot decide it") {
                decide(approved.id, """{"outcome":"DECLINED","reason":"POLICY","version":${approved.version}}""")
                    .andExpect(status().isConflict)
                    .andExpect(jsonPath("$.type").value("/problems/not-referred"))
            }
        }

        Given("no such application") {
            Then("deciding it is 404") {
                decide(UUID.randomUUID(), """{"outcome":"APPROVED","version":0}""")
                    .andExpect(status().isNotFound)
            }
        }
    }
}
