package com.mettyoung.creditcardapplication.application

import com.mettyoung.creditcardapplication.audit.AuditEntry
import com.mettyoung.creditcardapplication.audit.AuditEventType
import com.mettyoung.creditcardapplication.audit.Audits
import com.mettyoung.creditcardapplication.support.DockerAvailable
import com.mettyoung.creditcardapplication.support.dockerIsAvailable
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Opens the timeline the way `api.js` does: plain HTTP, the placeholder header, and the browser's Accept. */
private fun timelineRequest(port: Int, applicationId: UUID, user: String, lastEventId: Long? = null): HttpRequest =
    HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/applications/$applicationId/timeline"))
        .header("X-User-Id", user)
        .header("Accept", "text/event-stream, application/problem+json")
        .apply { if (lastEventId != null) header("Last-Event-ID", lastEventId.toString()) }
        .timeout(Duration.ofSeconds(15))
        .GET().build()

private fun createDraft(http: HttpClient, port: Int, user: String): UUID {
    val created = http.send(HttpRequest.newBuilder(URI.create("http://localhost:$port/v1/applications"))
        .header("X-User-Id", user).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("""{"cardProductCode":"CLASSIC"}""")).build(),
        HttpResponse.BodyHandlers.ofString())
    return UUID.fromString(Regex(""""id":"([^"]+)"""").find(created.body())!!.groupValues[1])
}

/**
 * FR11 over real HTTP: what a browser receives from the timeline stream. A real port rather than MockMvc, whose async
 * dispatch hands back a result, not a long-lived stream.
 *
 * The events are recorded straight through [Audits] and the application made terminal by SQL: how an application
 * gets there is FR5 and FR8's, covered by `IdentityVerificationTest`. Here only the stream's reaction matters.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.workers.enabled=false", "app.ui.timeline.enabled=true"],
)
@EnabledIf(DockerAvailable::class)
class TimelineStreamTest : BehaviorSpec() {

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

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var audits: Audits
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val user = "u_" + UUID.randomUUID()
    private val http = HttpClient.newHttpClient()

    init {
        extension(SpringExtension())

        fun retry(applicationId: UUID, attempt: Int) = TransactionTemplate(transactionManager).executeWithoutResult {
            audits.record(AuditEntry.bySystem(applicationId, AuditEventType.VENDOR_CHECK_RETRY,
                mapOf("attempt" to attempt, "failureCode" to "unavailable-503")))
        }

        fun makeTerminal(applicationId: UUID) =
            jdbc.update("UPDATE application SET status = 'APPROVED' WHERE id = ?", applicationId)

        fun seqsOf(applicationId: UUID): List<Long> = jdbc.queryForList(
            "SELECT seq FROM audit_event WHERE application_id = ? ORDER BY seq", Long::class.java, applicationId)
            .filterNotNull()

        /** The frames of an SSE body, each as its fields. Spring writes `id:14`, with no space after the colon. */
        fun framesOf(body: String): List<Map<String, String>> = body.split("\n\n").filter { it.isNotBlank() }
            .map { frame ->
                frame.lines().filter { ':' in it }
                    .associate { it.substringBefore(':') to it.substringAfter(':').removePrefix(" ") }
            }

        fun read(applicationId: UUID, asUser: String = user, lastEventId: Long? = null): HttpResponse<String> =
            http.send(timelineRequest(port, applicationId, asUser, lastEventId), HttpResponse.BodyHandlers.ofString())

        Given("an application that has reached its outcome") {
            val id = createDraft(http, port, user)
            retry(id, 1)
            retry(id, 2)
            makeTerminal(id)

            When("its owner opens the timeline") {
                val response = read(id)
                val frames = framesOf(response.body())

                Then("every event is sent in order, its seq as the event id, its type as the event name") {
                    response.statusCode() shouldBe 200
                    response.headers().firstValue("Content-Type").get() shouldContain "text/event-stream"
                    frames.map { it.getValue("id").toLong() } shouldContainExactly seqsOf(id)
                    frames.takeLast(2).map { it.getValue("event") } shouldContainExactly
                        listOf("VENDOR_CHECK_RETRY", "VENDOR_CHECK_RETRY")
                    frames.last().getValue("data") shouldContain """"attempt":2"""
                    frames.last().getValue("data") shouldContain """"seq":${seqsOf(id).last()}"""
                }

                // Getting a whole body back at all is FR11.4: the server ended the stream once there was nothing more.
            }

            When("a reconnecting browser sends the last event it has") {
                val all = seqsOf(id)
                val response = read(id, lastEventId = all.first())

                Then("the stream resumes after it, with nothing repeated or missed (FR11.3)") {
                    framesOf(response.body()).map { it.getValue("id").toLong() } shouldContainExactly all.drop(1)
                }
            }

            When("another applicant asks for it") {
                val response = read(id, asUser = "u_" + UUID.randomUUID())

                Then("it is not found, as for every other endpoint, and nothing is streamed (FR11.5)") {
                    response.statusCode() shouldBe 404
                    response.headers().firstValue("Content-Type").get() shouldContain "application/problem+json"
                }
            }
        }

        Given("an application still in progress") {
            When("its timeline is open as an event is recorded, and then the application reaches its outcome") {
                // Opened inside the When: InstancePerTest also runs the Given alone, which would leave a stream open.
                val id = createDraft(http, port, user)
                val open = http.sendAsync(timelineRequest(port, id, user), HttpResponse.BodyHandlers.ofString())
                // Long enough for the ticker to poll at least once while the application is still in progress.
                Thread.sleep(1500)
                open.isDone shouldBe false
                retry(id, 1)
                makeTerminal(id)
                val response = open.get(10, TimeUnit.SECONDS)

                Then("the late event arrives on the open stream, which then ends (FR11.1, FR11.4)") {
                    val frames = framesOf(response.body())
                    frames.shouldNotBeEmpty()
                    frames.last().getValue("event") shouldBe "VENDOR_CHECK_RETRY"
                    frames.map { it.getValue("id").toLong() } shouldContainExactly seqsOf(id)
                }
            }
        }
    }
}

/** FR11.5 / "no tipping off": with the property off - production's default - the endpoint does not exist. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["app.workers.enabled=false", "app.ui.timeline.enabled=false"],
)
@EnabledIf(DockerAvailable::class)
class TimelineDisabledTest : BehaviorSpec() {

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

    @LocalServerPort private var port: Int = 0

    init {
        extension(SpringExtension())

        Given("the timeline switched off") {
            val user = "u_" + UUID.randomUUID()
            val http = HttpClient.newHttpClient()
            val id = createDraft(http, port, user)

            When("the application's owner asks for it") {
                val response = http.send(timelineRequest(port, id, user), HttpResponse.BodyHandlers.ofString())

                Then("it is not found, so a production build does not reveal the endpoint exists") {
                    response.statusCode() shouldBe 404
                }
            }
        }
    }
}
