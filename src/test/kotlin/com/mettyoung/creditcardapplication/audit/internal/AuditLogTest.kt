package com.mettyoung.creditcardapplication.audit.internal

import com.mettyoung.creditcardapplication.audit.Actor
import com.mettyoung.creditcardapplication.audit.AuditEntry
import com.mettyoung.creditcardapplication.audit.AuditEventType
import com.mettyoung.creditcardapplication.audit.Audits

import com.mettyoung.creditcardapplication.support.DockerAvailable
import com.mettyoung.creditcardapplication.support.dockerIsAvailable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * In the module's own package because the repository it reads back through is package-private — the spec is
 * inside the boundary it is testing, which is the only place the rows are visible from.
 * <p>
 * Every invariant this module has is enforced by the database or by the transaction manager, so none of them
 * can be shown in memory — hence a real Postgres rather than a unit spec.
 */
@SpringBootTest
@EnabledIf(DockerAvailable::class)
class AuditLogTest : DescribeSpec() {

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
    private lateinit var auditLog: Audits

    @Autowired
    private lateinit var events: AuditEventRepository

    // Raw SQL on purpose: the module exposes no setter and no delete, so going around the mapping is the only
    // way to attempt what the trigger exists to refuse — and it is what an operator with psql would do.
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    // A fresh instance per test, so each gets its own application id and never sees another's rows.
    override fun isolationMode() = IsolationMode.InstancePerTest

    private val applicationId: UUID = UUID.randomUUID()

    private val json = JsonMapper.builder().build()

    init {
        extension(SpringExtension())

        @Suppress("UNCHECKED_CAST")
        fun storedPayload(): Map<String, Any?> = json.readValue(
            jdbc.queryForObject("SELECT payload::text FROM audit_event WHERE application_id = ?",
                String::class.java, applicationId)!!,
            Map::class.java) as Map<String, Any?>

        // The audit log never opens its own transaction, so every write here has to supply one.
        fun <T> inTransaction(work: () -> T): T =
            TransactionTemplate(transactionManager).execute { work() }!!

        fun append(id: UUID = applicationId, type: AuditEventType = AuditEventType.UPLOAD_REQUESTED,
                   payload: Map<String, Any> = mapOf("documentId" to UUID.randomUUID())) =
            inTransaction { auditLog.record(AuditEntry.byApplicant(id, type, "u_1", payload)) }

        describe("writing to the log") {

            it("refuses to append with no transaction of its own to join") {
                // The whole point of MANDATORY: without it this call would quietly open and commit its own
                // transaction, leaving a log entry that could outlive a rolled-back change.
                shouldThrow<IllegalTransactionStateException> {
                    auditLog.record(AuditEntry.byApplicant(applicationId, AuditEventType.UPLOAD_REQUESTED,
                        "u_1", mapOf("documentId" to UUID.randomUUID())))
                }
            }

            it("numbers the first event 1 and each later one contiguously") {
                repeat(3) { append() }

                events.findByApplicationIdOrderBySeq(applicationId).map { it.seq } shouldBe listOf(1L, 2L, 3L)
            }

            it("counts seq per application, so one application's log has no gaps from another's") {
                val other = UUID.randomUUID()
                append()
                append(id = other)
                append()

                events.findByApplicationIdOrderBySeq(applicationId).map { it.seq } shouldBe listOf(1L, 2L)
                events.findByApplicationIdOrderBySeq(other).map { it.seq } shouldBe listOf(1L)
            }

            it("records the actor that caused the event") {
                inTransaction {
                    auditLog.record(AuditEntry.bySystem(applicationId, AuditEventType.DOCUMENT_VERIFIED,
                        mapOf("status" to "UPLOADED")))
                }

                val event = events.findByApplicationIdOrderBySeq(applicationId).single()
                event.actor shouldBe Actor.SYSTEM
                event.actorId shouldBe null
            }
        }

        describe("two writers racing for the same application") {

            it("never lets both take the same seq") {
                // I10 is a unique index precisely because seq is read-then-written. AuditLog's comment says
                // the caller's transaction already serialises writers by holding the application row's lock;
                // there is no application row here, so this measures the backstop alone - and most attempts
                // lose, typically all but one. That is the point: unserialised writers do not quietly produce
                // duplicate seq values, they fail. Whatever commits must still read back as 1..n with no gap.
                val writers = 8

                val outcomes = runConcurrently(writers) {
                    runCatching { append(payload = mapOf("attempt" to it)) }
                }

                val committed = events.findByApplicationIdOrderBySeq(applicationId)
                committed.map { it.seq } shouldBe (1L..committed.size.toLong()).toList()
                committed.size shouldBe outcomes.count { it.isSuccess }
            }
        }

        describe("the append-only guarantee") {

            it("refuses an UPDATE, whoever is connected") {
                // I9. A REVOKE would not do this: the application owns the table, and an owner keeps its
                // rights whatever is revoked. Only the trigger holds.
                append()

                shouldThrowAny {
                    jdbc.update("UPDATE audit_event SET type = ? WHERE application_id = ?",
                        AuditEventType.DOCUMENT_VERIFIED.name, applicationId)
                }

                events.findByApplicationIdOrderBySeq(applicationId).single()
                    .type shouldBe AuditEventType.UPLOAD_REQUESTED
            }

            it("refuses a DELETE, so a row cannot be made to disappear") {
                append()

                shouldThrowAny {
                    jdbc.update("DELETE FROM audit_event WHERE application_id = ?", applicationId)
                }

                events.findByApplicationIdOrderBySeq(applicationId) shouldHaveSize 1
            }
        }

        describe("what a payload may carry") {

            it("stores the ids and codes it was given") {
                // Asserted positively as well as negatively: a column that came back empty would satisfy
                // "contains no declared data" while making the log useless.
                append(payload = mapOf("kind" to "ID", "sizeBytes" to 1024))

                storedPayload() shouldContainExactly mapOf<String, Any?>("kind" to "ID", "sizeBytes" to 1024)
            }

            it("carries no declared data, which is the half that has to stay true") {
                // The rule is the caller's to keep, but the log is where a leak would be durable.
                append(payload = mapOf("kind" to "ID", "sizeBytes" to 1024))

                val raw = jdbc.queryForObject(
                    "SELECT payload::text FROM audit_event WHERE application_id = ?", String::class.java,
                    applicationId)!!

                raw shouldNotContain "Jane"
                raw shouldNotContain "1990-04-12"
            }
        }
    }
}

/** The writes have to overlap for the unique index to be exercised at all. */
private fun <T> runConcurrently(threads: Int, task: (Int) -> T): List<T> =
    Executors.newFixedThreadPool(threads).use { pool ->
        val start = CountDownLatch(1)
        val futures = (0 until threads).map { i -> pool.submit(Callable { start.await(); task(i) }) }
        start.countDown()
        futures.map { it.get(30, TimeUnit.SECONDS) }
    }
