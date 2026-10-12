package com.mettyoung.creditcardapplication.vendor.internal

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
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * The inbox worker and the reconciler settle the same waiting check by design. The conditional updates are what
 * make that safe, so they are raced for real: each attempt runs in its own transaction, released together, against
 * Postgres. Workers are off so nothing else touches the row.
 */
@SpringBootTest
@TestPropertySource(properties = ["app.workers.enabled=false"])
@EnabledIf(DockerAvailable::class)
class CallbackRaceTest : BehaviorSpec() {

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

    @Autowired private lateinit var checks: VendorCheckRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    override fun isolationMode() = IsolationMode.InstancePerTest

    init {
        extension(SpringExtension())

        val now = Instant.now()

        fun awaiting(): VendorCheck = checks.saveAndFlush(
            VendorCheck.queueIdv(UUID.randomUUID(), "onfido", UUID.randomUUID(), now).apply {
                claim(now, Duration.ofMinutes(1))
                awaitCallback(VendorRef("chk_" + UUID.randomUUID()), "{}", now, Duration.ofMinutes(30), Duration.ofSeconds(10))
            })

        fun <T> race(attempts: Int, task: (Int) -> T): List<T> {
            val transaction = TransactionTemplate(transactionManager)
            return Executors.newFixedThreadPool(attempts).use { pool ->
                val start = CountDownLatch(1)
                val futures = (0 until attempts).map { i ->
                    pool.submit(Callable { start.await(); transaction.execute { task(i) }!! })
                }
                start.countDown()
                futures.map { it.get() }
            }
        }

        Given("a check waiting for its callback") {
            val check = awaiting()

            When("ten completions arrive at once, as the webhook and the reconciler") {
                val updated = race(10) { checks.completeIfAwaiting(check.id, IdvOutcome.VERIFIED, """{"result":"clear"}""", now) }

                Then("exactly one changes the row; the rest update nothing and throw nothing") {
                    updated.sum() shouldBe 1
                    updated.count { it == 0 } shouldBe 9
                }

                Then("the row is completed once, with the version bumped once") {
                    val stored = checks.findById(check.id).orElseThrow()
                    stored.status shouldBe CheckStatus.COMPLETED
                    stored.outcome shouldBe IdvOutcome.VERIFIED
                    stored.version shouldBe check.version!! + 1
                }
            }

            When("the deadline and a late webhook settle it at the same moment") {
                val updated = race(10) { i ->
                    if (i % 2 == 0) checks.completeIfAwaiting(check.id, IdvOutcome.VERIFIED, "{}", now)
                    else checks.failIfAwaiting(check.id, "deadline-exceeded", now)
                }

                Then("one of them wins, and the check ends either completed or failed, never both") {
                    updated.sum() shouldBe 1
                    val stored = checks.findById(check.id).orElseThrow()
                    (stored.status == CheckStatus.COMPLETED || stored.status == CheckStatus.FAILED) shouldBe true
                    (stored.status == CheckStatus.COMPLETED) shouldBe (stored.failureCode == null)
                }
            }
        }

        Given("a check that is no longer waiting") {
            val check = awaiting()
            // A modifying query needs a transaction, as both workers give it.
            TransactionTemplate(transactionManager).execute { checks.completeIfAwaiting(check.id, IdvOutcome.FRAUD, "{}", now) }

            When("another completion or the deadline arrives later") {
                Then("neither changes it") {
                    TransactionTemplate(transactionManager).execute {
                        checks.completeIfAwaiting(check.id, IdvOutcome.VERIFIED, "{}", now) shouldBe 0
                        checks.failIfAwaiting(check.id, "deadline-exceeded", now) shouldBe 0
                    }
                    checks.findById(check.id).orElseThrow().outcome shouldBe IdvOutcome.FRAUD
                }
            }
        }
    }
}
