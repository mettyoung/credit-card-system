package com.mettyoung.creditcardapplication.shared.outbox

import com.mettyoung.creditcardapplication.support.DockerAvailable
import com.mettyoung.creditcardapplication.support.dockerIsAvailable
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A claim is a column, not a lock. The row lock `FOR UPDATE SKIP LOCKED` takes dies when the claiming
 * transaction commits, and the relay dispatches each row in a *later* transaction — so without a persisted
 * claim, a second relay re-selects the same rows the moment the first one commits.
 *
 * These run against real Postgres because every one of them is about what survives a commit.
 */
@SpringBootTest(properties = ["app.workers.enabled=false"])
@EnabledIf(DockerAvailable::class)
class OutboxClaimTest : DescribeSpec() {

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

    @Autowired
    private lateinit var outbox: OutboxRepository

    @Autowired
    private lateinit var processed: ProcessedEventRepository

    @Autowired
    private lateinit var relay: OutboxRelay

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val applicationId: UUID = UUID.randomUUID()

    init {
        extension(SpringExtension())

        val now: Instant = Instant.parse("2026-09-30T10:00:00Z")

        fun <T> inTransaction(work: () -> T): T = TransactionTemplate(transactionManager).execute { work() }!!

        fun write(count: Int): List<UUID> = inTransaction {
            (1..count).map {
                outbox.save(OutboxEvent.of(DomainEvent.ApplicationSubmitted(applicationId),
                    """{"applicationId":"$applicationId"}""", now)).id
            }
        }

        describe("claiming a batch") {

            it("hands the same rows to nobody else once the claiming transaction has committed") {
                val ids = write(2)

                inTransaction { outbox.claim("relay-a", now.plus(Duration.ofMinutes(1)), ids) }

                // The lock is long gone; only the column keeps a second relay away.
                outbox.selectClaimable(now, 10).filter { it in ids }.shouldBeEmpty()
            }

            it("offers them again once the lease has run out, so a dead relay strands nothing") {
                val ids = write(2)
                inTransaction { outbox.claim("relay-a", now.plus(Duration.ofMinutes(1)), ids) }

                val afterExpiry = outbox.selectClaimable(now.plus(Duration.ofMinutes(2)), 10)

                // In any order: these two ids were generated in the same millisecond, and UUIDv7 is only
                // time-ordered across milliseconds.
                afterExpiry.filter { it in ids } shouldContainExactlyInAnyOrder ids
            }

            it("offers a released row on the next poll rather than waiting out the lease") {
                val ids = write(1)
                inTransaction { outbox.claim("relay-a", now.plus(Duration.ofMinutes(1)), ids) }

                inTransaction { outbox.releaseClaim(ids.single()) }

                outbox.selectClaimable(now, 10).filter { it in ids } shouldContainExactly ids
            }

            // Releasing the claim from a *failed* dispatch has no test, and cannot have one yet. The
            // failure would have to come from a constraint inside dispatchOne, and the guard meant to raise
            // it - the processed_event primary key - does not fire: save() merges an existing marker rather
            // than refusing it. Until that is an explicit INSERT ... ON CONFLICT DO NOTHING, a redelivery is
            // caught by the publishedAt check instead and the dispatch simply succeeds. The release itself
            // is covered by the case above, one layer down.

            it("never offers a published row, claimed or not") {
                val ids = write(1)
                inTransaction {
                    val event = outbox.findById(ids.single()).orElseThrow()
                    event.markPublished(now)
                    outbox.save(event)
                }

                outbox.selectClaimable(now, 10).filter { it in ids }.shouldBeEmpty()
            }
        }
    }
}
