package com.mettyoung.creditcardapplication.application.internal

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.mettyoung.creditcardapplication.application.ApplicationStatus
import com.mettyoung.creditcardapplication.application.CardProduct
import com.mettyoung.creditcardapplication.application.DecisionReason
import com.mettyoung.creditcardapplication.application.UpdateDraftCommand
import com.mettyoung.creditcardapplication.shared.outbox.OutboxRelay
import com.mettyoung.creditcardapplication.support.DockerAvailable
import com.mettyoung.creditcardapplication.support.dockerIsAvailable
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * FR9: the sweep finds what is overdue by its column, reports a NEEDS_INFO expiry through the outbox and an
 * overdue referral to the log, and never transitions anything itself.
 */
@SpringBootTest
@TestPropertySource(properties = ["app.workers.enabled=false"])
@EnabledIf(DockerAvailable::class)
class DeadlineSweepTest : BehaviorSpec() {

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

    @Autowired private lateinit var sweep: DeadlineSweep
    @Autowired private lateinit var relay: OutboxRelay
    @Autowired private lateinit var repository: ApplicationRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val user = "u_" + UUID.randomUUID()
    private val at = Instant.now()

    init {
        extension(SpringExtension())

        fun submitted(): Application = Application.createDraft(user, CardProduct.CLASSIC).apply {
            on(UpdateDraftCommand("Jane", "Tan", LocalDate.of(1990, 4, 12), "SG", 0L))
            submit(true, at)
            startVerifying(at)
        }

        fun saved(application: Application, waitingSince: String): UUID {
            val id = repository.saveAndFlush(application).id
            jdbc.update("UPDATE application SET status_changed_at = now() - interval '$waitingSince' WHERE id = ?", id)
            return id
        }

        fun expiriesFor(id: UUID): Long = jdbc.queryForObject(
            "SELECT count(*) FROM outbox WHERE application_id = ? AND type = 'NeedsInfoExpired'", Long::class.java, id)!!

        fun drainRelay() {
            repeat(10) { if (relay.dispatchDue() == 0) return }
        }

        Given("one application past its NEEDS_INFO deadline, one inside it, and an overdue referral") {
            val overdue = saved(submitted().apply { requestInfo(at) }, "31 days")
            val inside = saved(submitted().apply { requestInfo(at) }, "1 day")
            val referral = saved(submitted().apply {
                completeChecks(at)
                refer(DecisionReason.FRAUD_SUSPECTED, at)
            }, "3 days")

            When("the sweep runs") {
                val logs = captureLogs { sweep.sweep() }

                Then("it reports the overdue one through the outbox, and not the one still inside its deadline") {
                    expiriesFor(overdue) shouldBe 1
                    expiriesFor(inside) shouldBe 0
                }

                Then("it changes no status itself - the orchestrator decides") {
                    repository.findById(overdue).orElseThrow().status shouldBe ApplicationStatus.NEEDS_INFO
                    repository.findById(referral).orElseThrow().status shouldBe ApplicationStatus.REFERRED
                }

                Then("once the orchestrator has expired it, the next sweep reports it no more") {
                    drainRelay()
                    repository.findById(overdue).orElseThrow().status shouldBe ApplicationStatus.EXPIRED

                    sweep.sweep()

                    expiriesFor(overdue) shouldBe 1
                }

                Then("it warns about the overdue referral with a count, and nothing personal") {
                    logs shouldContain "referred applications are overdue"
                    logs shouldNotContain "Jane"
                    logs shouldNotContain "Tan"
                    logs shouldNotContain referral.toString()
                }
            }
        }
    }
}

/**
 * Spring's OutputCaptureExtension is a JUnit parameter resolver, which Kotest does not run, so the log is
 * captured through logback.
 */
private fun captureLogs(block: () -> Unit): String {
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    root.addAppender(appender)
    try {
        block()
    } finally {
        root.detachAppender(appender)
        appender.stop()
    }
    return appender.list.joinToString("\n") { it.formattedMessage }
}
