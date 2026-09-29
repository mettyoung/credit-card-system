package com.mettyoung.creditcardapplication.application.internal

import com.mettyoung.creditcardapplication.support.DockerAvailable
import com.mettyoung.creditcardapplication.support.dockerIsAvailable
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * The upload endpoints end to end: real controller, service, aggregate, Hibernate, Postgres and a real S3.
 *
 * The bytes go to the store over HTTP through the pre-signed URL the API issued, exactly as a client's would,
 * so `/complete` is verifying an object this spec never wrote through a back door. That is the only way the
 * checks it performs mean anything.
 */
@SpringBootTest(properties = ["app.workers.enabled=false"])
@AutoConfigureMockMvc
@EnabledIf(DockerAvailable::class)
class DocumentControllerTest : BehaviorSpec() {

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        val objectStore: GenericContainer<*> = GenericContainer("localstack/localstack:3")
            .withExposedPorts(4566)
            .withEnv("SERVICES", "s3")
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566))

        init {
            if (dockerIsAvailable()) {
                postgres.start()
                objectStore.start()
                System.setProperty("app.storage.endpoint",
                    "http://${objectStore.host}:${objectStore.getMappedPort(4566)}")
            }
        }
    }

    @Autowired
    private lateinit var mvc: MockMvc

    // Only to read back what the audit log holds; every action goes through HTTP.
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val user = "u_" + UUID.randomUUID()

    init {
        extension(SpringExtension())

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(1021)
        val pdf = "%PDF-".toByteArray() + ByteArray(1019)

        fun digestOf(bytes: ByteArray) =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun body(kind: String = "ID", contentType: String = "image/jpeg", sizeBytes: Int = jpeg.size,
                 sha256: String = digestOf(jpeg)) =
            """{"kind":"$kind","contentType":"$contentType","sizeBytes":$sizeBytes,"sha256":"$sha256"}"""

        fun createApplication(): UUID {
            val created = mvc.perform(post("/v1/applications")
                .header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"cardProductCode":"CLASSIC"}"""))
                .andExpect(status().isCreated)
                .andReturn()
            return UUID.fromString(
                Regex(""""id":"([^"]+)"""").find(created.response.contentAsString)!!.groupValues[1])
        }

        fun requestUpload(applicationId: UUID, payload: String = body()): MvcResult =
            mvc.perform(post("/v1/applications/$applicationId/documents")
                .header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andReturn()

        /** PUTs to the URL the API just issued, with the headers it said were required. */
        fun putBytes(issued: String, bytes: ByteArray, declaredContentType: String? = null) {
            val url = Regex(""""url":"([^"]+)"""").find(issued)!!.groupValues[1]
            val headers = Regex(""""requiredHeaders":\{([^}]*)}""").find(issued)!!.groupValues[1]
            var request = HttpRequest.newBuilder(URI.create(url.replace("\\u003d", "=")))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
            Regex(""""([^"]+)":"([^"]+)"""").findAll(headers).forEach { match ->
                val (name, value) = match.destructured
                request = request.header(name, if (name == "Content-Type") declaredContentType ?: value else value)
            }
            HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding())
        }

        fun documentIdOf(result: MvcResult) =
            Regex(""""documentId":"([^"]+)"""").find(result.response.contentAsString)!!.groupValues[1]

        fun complete(applicationId: UUID, documentId: String) =
            mvc.perform(post("/v1/applications/$applicationId/documents/$documentId/complete")
                .header("X-User-Id", user))

        fun auditTypes(applicationId: UUID): List<String> = jdbc
            .queryForList("SELECT type FROM audit_event WHERE application_id = ? ORDER BY seq",
                String::class.java, applicationId)
            .filterNotNull()

        Given("an application that can still take evidence") {

            When("the applicant asks for an upload URL") {
                val applicationId = createApplication()
                val issued = requestUpload(applicationId)

                Then("it answers 201 with a URL and the headers the store will insist on") {
                    issued.response.status shouldBe 201
                    val payload = issued.response.contentAsString
                    payload shouldNotContain "\"applicationId\""
                    Regex(""""method":"PUT"""").containsMatchIn(payload) shouldBe true
                    Regex("x-amz-checksum-sha256").containsMatchIn(payload) shouldBe true
                }

                Then("FR3.5: the request is in the audit log before any object exists") {
                    auditTypes(applicationId) shouldBe listOf("UPLOAD_REQUESTED")
                }
            }

            When("a declared attribute breaks a rule") {
                val applicationId = createApplication()

                Then("the field is named, and the value never echoed") {
                    val refused = requestUpload(applicationId, body(contentType = "image/gif"))

                    refused.response.status shouldBe 422
                    refused.response.contentAsString shouldNotContain "image/gif"
                    Regex(""""field":"contentType"""").containsMatchIn(refused.response.contentAsString) shouldBe true
                }
            }
        }

        Given("an upload URL that was issued") {

            When("the bytes match everything that was declared") {
                val applicationId = createApplication()
                val issued = requestUpload(applicationId)
                val documentId = documentIdOf(issued)
                putBytes(issued.response.contentAsString, jpeg)

                Then("FR3.2: /complete accepts it") {
                    complete(applicationId, documentId)
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.status").value("UPLOADED"))
                        .andExpect(jsonPath("$.reason").doesNotExist())
                }

                Then("FR3.5: the verdict is logged next to the request") {
                    complete(applicationId, documentId).andExpect(status().isOk)

                    auditTypes(applicationId) shouldBe listOf("UPLOAD_REQUESTED", "DOCUMENT_VERIFIED")
                }

                Then("replaying it reaches the same answer rather than a second verdict") {
                    complete(applicationId, documentId).andExpect(status().isOk)

                    complete(applicationId, documentId)
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.status").value("UPLOADED"))
                }
            }

            When("nothing was ever PUT") {
                val applicationId = createApplication()
                val documentId = documentIdOf(requestUpload(applicationId))

                Then("it is a conflict the applicant can fix, not a verdict") {
                    complete(applicationId, documentId)
                        .andExpect(status().isConflict)
                        .andExpect(jsonPath("$.type").value("/problems/upload-incomplete"))
                }
            }

            When("the bytes are a PDF wearing a JPEG's content type") {
                val applicationId = createApplication()
                // Declared as a JPEG, and the checksum is honest - of the PDF. The store is satisfied: it
                // never looks inside the file. Only the magic-byte check can catch this.
                val issued = requestUpload(applicationId,
                    body(sizeBytes = pdf.size, sha256 = digestOf(pdf)))
                val documentId = documentIdOf(issued)
                putBytes(issued.response.contentAsString, pdf)

                Then("FR3.2: it is INVALID, and a 200 - the call was fine, the answer is no") {
                    complete(applicationId, documentId)
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.status").value("INVALID"))
                        .andExpect(jsonPath("$.reason").value("content-type-mismatch"))
                }
            }
        }

        Given("an application with everything submit needs") {

            When("the applicant submits it") {
                val applicationId = createApplication()
                mvc.perform(patch("/v1/applications/$applicationId")
                    .header("X-User-Id", user)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"firstName":"Jane","lastName":"Tan","dateOfBirth":"1990-04-12","country":"SG","version":0}"""))
                    .andExpect(status().isOk)
                val issued = requestUpload(applicationId)
                putBytes(issued.response.contentAsString, jpeg)
                complete(applicationId, documentIdOf(issued)).andExpect(status().isOk)

                Then("it is accepted") {
                    mvc.perform(post("/v1/applications/$applicationId/submit").header("X-User-Id", user))
                        .andExpect(status().isAccepted)
                        .andExpect(jsonPath("$.status").value("SUBMITTED"))
                }

                Then("the aggregate's event produced both rows, in the submitting transaction") {
                    // @DomainEvents publishes inside save(), so ApplicationEffects runs before the commit.
                    // If publication did not happen at all - which is what a repository method Spring Data
                    // does not intercept would look like - the transition would still commit and these two
                    // would simply be missing.
                    mvc.perform(post("/v1/applications/$applicationId/submit").header("X-User-Id", user))
                        .andExpect(status().isAccepted)

                    auditTypes(applicationId).last() shouldBe "WORKFLOW_STARTED"
                    jdbc.queryForObject(
                        "SELECT count(*) FROM outbox WHERE application_id = ? AND type = 'ApplicationSubmitted'",
                        Int::class.java, applicationId) shouldBe 1
                }
            }
        }

        Given("a document that belongs to someone else") {

            When("a different applicant asks for it") {
                val applicationId = createApplication()
                val documentId = documentIdOf(requestUpload(applicationId))

                Then("absent and not-yours are one answer, so ids cannot be probed") {
                    mvc.perform(get("/v1/applications/$applicationId/documents/$documentId")
                        .header("X-User-Id", "u_" + UUID.randomUUID()))
                        .andExpect(status().isNotFound)
                }
            }

            When("the owner asks for it") {
                val applicationId = createApplication()
                val documentId = documentIdOf(requestUpload(applicationId))

                Then("FR3.3: it reports kind and status, and never the application id") {
                    mvc.perform(get("/v1/applications/$applicationId/documents/$documentId")
                        .header("X-User-Id", user))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.kind").value("ID"))
                        .andExpect(jsonPath("$.status").value("PENDING_UPLOAD"))
                        .andExpect(jsonPath("$.applicationId").doesNotExist())
                }
            }
        }
    }
}
