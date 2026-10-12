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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * I4 / FR1.5: one live application per user per card product. The earlier application is built through the
 * aggregate's own transitions and saved, rather than driven through a vendor: what is under test is which states
 * hold the slot, and the paths to them are the other specs'. Workers are off so nothing moves a seeded row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.workers.enabled=false"])
@EnabledIf(DockerAvailable::class)
class OneLiveApplicationTest : BehaviorSpec() {

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

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val user = "u_" + UUID.randomUUID()
    private val at = Instant.now()

    init {
        extension(SpringExtension())

        fun submitted(): Application = Application.createDraft(user, CardProduct.CLASSIC).apply {
            on(UpdateDraftCommand("Jane", "Tan", LocalDate.of(1990, 4, 12), "SG", 0L))
            submit(true, at)
        }

        fun verifying() = submitted().apply { startVerifying(at) }
        fun needsInfo() = verifying().apply { requestInfo(at) }
        fun checksComplete() = verifying().apply { completeChecks(at) }
        fun referred() = checksComplete().apply { refer(DecisionReason.FRAUD_SUSPECTED, at) }

        // Each earlier application, by the status it was left in.
        val holdsTheSlot = mapOf(
            ApplicationStatus.SUBMITTED to ::submitted,
            ApplicationStatus.VERIFYING to ::verifying,
            ApplicationStatus.NEEDS_INFO to ::needsInfo,
            ApplicationStatus.CHECKS_COMPLETE to ::checksComplete,
            ApplicationStatus.REFERRED to ::referred,
            ApplicationStatus.APPROVED to { checksComplete().apply { approve(at) } },
        )
        val freesTheSlot = mapOf(
            ApplicationStatus.DECLINED to { referred().apply { declineOnReview(DecisionReason.FRAUD_CONFIRMED, at) } },
            // NEEDS_INFO past its deadline; the aggregate does not re-check the deadline, the orchestrator does.
            ApplicationStatus.EXPIRED to { needsInfo().apply { expire(at) } },
        )

        fun create(product: CardProduct = CardProduct.CLASSIC): ResultActions =
            mvc.perform(post("/v1/applications").header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON).content("""{"cardProductCode":"$product"}"""))

        holdsTheSlot.forEach { (state, build) ->
            Given("an earlier application for the card that is $state") {
                val earlier = repository.saveAndFlush(build())
                earlier.status shouldBe state

                When("the applicant creates another for the same card") {
                    val response = create().andExpect(status().isConflict)
                        .andExpect(jsonPath("$.type").value("/problems/application-already-exists"))
                        .andExpect(jsonPath("$.applicationId").value(earlier.id.toString()))
                        .andReturn().response.contentAsString

                    Then("it is refused, without saying where the earlier one stands") {
                        response shouldNotContain state.name
                        response shouldNotContain "FRAUD"
                    }

                    Then("no second row is written") {
                        repository.listFor(user, null) shouldHaveSize 1
                    }
                }

                When("they create one for a different card") {
                    Then("that is allowed: the rule is per card product") {
                        create(CardProduct.PLATINUM).andExpect(status().isCreated)
                    }
                }
            }
        }

        freesTheSlot.forEach { (state, build) ->
            Given("an earlier application for the card that is $state") {
                repository.saveAndFlush(build()).status shouldBe state

                When("the applicant applies again") {
                    Then("a new draft is created beside it") {
                        create().andExpect(status().isCreated)
                        repository.listFor(user, null) shouldHaveSize 2
                    }
                }
            }
        }

        Given("a declined application, and ten reapplications sent at once") {
            repository.saveAndFlush(referred().apply { declineOnReview(DecisionReason.POLICY, at) })

            When("the requests overlap") {
                val responses = runConcurrently(10) {
                    create().andReturn().response.let { it.status to it.contentAsString }
                }

                Then("exactly one draft is created, and the rest are told it is a draft they can carry on with") {
                    responses.count { it.first == 201 } shouldBe 1
                    responses.filter { it.first == 409 }.map { Regex(""""type":"([^"]+)"""").find(it.second)!!.groupValues[1] }
                        .toSet() shouldBe setOf("/problems/draft-already-exists")
                    responses.count { it.first == 409 } shouldBe 9
                }

                Then("the index left the declined one and a single draft") {
                    repository.listFor(user, null) shouldHaveSize 2
                }
            }
        }
    }
}
