package com.mettyoung.creditcardapplication.vendor.internal

import com.mettyoung.creditcardapplication.application.ApplicationStatus
import com.mettyoung.creditcardapplication.support.DockerAvailable
import com.mettyoung.creditcardapplication.support.IdentityContainers
import com.mettyoung.creditcardapplication.application.RequirementStatus
import com.mettyoung.creditcardapplication.application.RequirementType
import com.mettyoung.creditcardapplication.document.DocumentStatus
import com.mettyoung.creditcardapplication.shared.outbox.OutboxRelay
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.string.shouldNotContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * FR3–FR5 end to end: upload an ID (FR3), submit (FR4), have the Onfido mock verify it (FR5), reach
 * CHECKS_COMPLETE.
 *
 * Real stack throughout — Postgres, MinIO and WireMock in containers, bytes PUT to a genuine pre-signed URL,
 * the webhook arriving with a genuine HMAC. Nothing is mocked in-process.
 *
 * The workers are driven by hand (`app.workers.enabled=false`). With the scheduler running, an assertion like
 * "exactly one POST /checks" would race a background poll and pass or fail on timing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.workers.enabled=false", "app.onfido.poll-after=0s"])
@EnabledIf(DockerAvailable::class)
class IdentityVerificationTest : BehaviorSpec() {

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres = IdentityContainers.postgres

        init {
            IdentityContainers.start()
        }
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var checks: VendorCheckRepository
    @Autowired private lateinit var inbox: VendorInboxRepository
    // Other modules keep their repositories to themselves, so what they stored is read as rows.
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var relay: OutboxRelay
    @Autowired private lateinit var vendorWorker: VendorWorker
    @Autowired private lateinit var inboxWorker: InboxWorker
    @Autowired private lateinit var reconciler: Reconciler

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val user = "u_" + UUID.randomUUID()
    private val http = HttpClient.newHttpClient()

    init {
        extension(SpringExtension())

        // ---------- helpers ----------

        fun form(lastName: String) =
            """{"firstName":"Jane","lastName":"$lastName","dateOfBirth":"1990-04-12","country":"SG","version":0}"""

        fun createDraft(lastName: String): UUID {
            val body = mvc.perform(post("/v1/applications").header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON).content("""{"cardProductCode":"CLASSIC"}"""))
                .andExpect(status().isCreated).andReturn().response.contentAsString
            val id = UUID.fromString(Regex(""""id":"([^"]+)"""").find(body)!!.groupValues[1])
            mvc.perform(patch("/v1/applications/$id").header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON).content(form(lastName)))
                .andExpect(status().isOk)
            return id
        }

        /** A real JPEG header, so the magic-byte check has something genuine to accept. */
        fun jpegBytes(size: Int = 64) = ByteArray(size).also {
            it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte()
        }

        fun sha256Hex(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun sha256Base64(bytes: ByteArray): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

        /** Requests a URL, PUTs the bytes to it for real, then confirms. Returns the document id. */
        fun uploadId(applicationId: UUID, bytes: ByteArray = jpegBytes(),
                     declaredSha: String? = null): Pair<UUID, String> {
            val sha = declaredSha ?: sha256Hex(bytes)
            val issued = mvc.perform(post("/v1/applications/$applicationId/documents")
                .header("X-User-Id", user).contentType(MediaType.APPLICATION_JSON)
                .content("""{"kind":"ID","contentType":"image/jpeg","sizeBytes":${bytes.size},"sha256":"$sha"}"""))
                .andExpect(status().isCreated).andReturn().response.contentAsString

            val documentId = UUID.fromString(Regex(""""documentId":"([^"]+)"""").find(issued)!!.groupValues[1])
            val url = Regex(""""url":"([^"]+)"""").find(issued)!!.groupValues[1].replace("\\u003d", "=")

            val put = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "image/jpeg")
                .header("x-amz-checksum-sha256", sha256Base64(bytes))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build()
            val response = http.send(put, HttpResponse.BodyHandlers.ofString())

            val completed = mvc.perform(post(
                "/v1/applications/$applicationId/documents/$documentId/complete").header("X-User-Id", user))
                .andExpect(status().isOk).andReturn().response.contentAsString
            return documentId to "${response.statusCode()}|$completed"
        }

        fun submit(applicationId: UUID) =
            mvc.perform(post("/v1/applications/$applicationId/submit").header("X-User-Id", user))

        fun signedWebhook(vendorRef: String, action: String = "check.completed"): Pair<String, String> {
            val body =
                """{"payload":{"resource_type":"check","action":"$action","object":{"id":"$vendorRef"}}}"""
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec("test-webhook-token".toByteArray(), "HmacSHA256"))
            return body to mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        fun postWebhook(vendorRef: String, signature: String? = null) =
            signedWebhook(vendorRef).let { (body, realSignature) ->
                mvc.perform(post("/webhooks/idv")
                    .header("X-SHA2-Signature", signature ?: realSignature)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
            }

        /** Runs the relay until it has nothing left, so the orchestrator has caught up. */
        fun drainRelay() {
            repeat(10) { if (relay.dispatchDue() == 0) return }
        }

        fun statusOf(applicationId: UUID): ApplicationStatus = ApplicationStatus.valueOf(
            jdbc.queryForObject("SELECT status FROM application WHERE id = ?", String::class.java, applicationId)!!)

        fun requirementStatusOf(applicationId: UUID, type: RequirementType): RequirementStatus =
            RequirementStatus.valueOf(jdbc.queryForObject(
                "SELECT status FROM evidence_requirement WHERE application_id = ? AND type = ?",
                String::class.java, applicationId, type.name)!!)

        fun auditTypesOf(applicationId: UUID): List<String> = jdbc
            .queryForList("SELECT type FROM audit_event WHERE application_id = ? ORDER BY seq",
                String::class.java, applicationId).filterNotNull()

        fun auditSeqOf(applicationId: UUID): List<Long> = jdbc
            .queryForList("SELECT seq FROM audit_event WHERE application_id = ? ORDER BY seq",
                Long::class.java, applicationId).filterNotNull()

        fun documentStatusesOf(applicationId: UUID): List<String> = jdbc
            .queryForList("SELECT status FROM document WHERE application_id = ?",
                String::class.java, applicationId).filterNotNull()

        /**
         * Runs a worker until this application's checks satisfy [done]. The vendor worker and the reconciler each
         * claim one batch of due checks across every test sharing the database, so with checks other scenarios
         * leave behind, a single call may never reach this one - and the vendorRef!! that follows would throw
         * inside a When, which Kotest reports by silently dropping its Thens.
         */
        fun runUntil(id: UUID, worker: () -> Unit, done: (List<VendorCheck>) -> Boolean) {
            repeat(20) {
                if (done(checks.findByApplicationId(id))) {
                    return
                }
                worker()
            }
        }

        /** Until the vendor worker has claimed this application's queued checks. */
        fun runVendorWorker(id: UUID,
                            done: (List<VendorCheck>) -> Boolean = { all -> all.none { it.status == CheckStatus.QUEUED } }) =
            runUntil(id, { vendorWorker.runDue() }, done)

        /** Until the reconciler has resolved this application's checks that were waiting for a callback. */
        fun runReconciler(id: UUID) = runUntil(id, { reconciler.reconcileDue() }) { all ->
            all.none { it.status == CheckStatus.AWAITING_CALLBACK }
        }

        /**
         * The vendor's reference for this application's only check - failing with the check's state rather than a
         * bare NullPointerException, which inside a When is all Kotest would report.
         */
        fun vendorRefOf(id: UUID): String {
            val check = checks.findByApplicationId(id).single()
            return withClue("check ${check.status}, attempts ${check.attempts}, failure ${check.failureCode}, " +
                "raw ${check.rawResponse?.take(300)}") { check.vendorRef.shouldNotBeNull().value() }
        }

        // ---------- scenarios ----------

        Given("an applicant with a verified ID document") {

            When("they submit and every worker runs in turn") {
                val id = createDraft("Tan")
                uploadId(id)
                submit(id).andExpect(status().isAccepted)
                    .andExpect(jsonPath("$.status").value("SUBMITTED"))

                Then("intake is recorded before any check exists") {
                    statusOf(id) shouldBe ApplicationStatus.SUBMITTED
                    checks.findByApplicationId(id).shouldHaveSize(0)
                }

                Then("the orchestrator queues exactly one IDV check and moves to VERIFYING") {
                    drainRelay()

                    statusOf(id) shouldBe ApplicationStatus.VERIFYING
                    val queued = checks.findByApplicationId(id)
                    queued.shouldHaveSize(1)
                    queued.first().status shouldBe CheckStatus.QUEUED
                    requirementStatusOf(id, RequirementType.IDENTITY) shouldBe RequirementStatus.PENDING
                }

                Then("the vendor worker submits it and waits for the callback") {
                    drainRelay()
                    runVendorWorker(id)

                    val check = checks.findByApplicationId(id).first()
                    // The failure code and the vendor's own body are on the clue, so a red test says why.
                    withClue("failureCode=${check.failureCode} raw=${check.rawResponse}") {
                        check.status shouldBe CheckStatus.AWAITING_CALLBACK
                        check.vendorRef.shouldNotBeNull()
                        check.attempts shouldBe 1
                    }
                }

                Then("the webhook, the inbox worker and the relay carry it to CHECKS_COMPLETE") {
                    drainRelay()
                    runVendorWorker(id)
                    val ref = vendorRefOf(id)

                    postWebhook(ref).andExpect(status().isOk)
                    inboxWorker.processDue()
                    drainRelay()

                    statusOf(id) shouldBe ApplicationStatus.CHECKS_COMPLETE
                    val check = checks.findByApplicationId(id).first()
                    check.status shouldBe CheckStatus.COMPLETED
                    check.outcome shouldBe IdvOutcome.VERIFIED
                    // The raw response is kept, so a later increment can rebuild the evidence.
                    check.rawResponse.shouldNotBeNull()
                    requirementStatusOf(id, RequirementType.IDENTITY) shouldBe RequirementStatus.RECEIVED
                }

                Then("the audit log is contiguous and holds no declared data") {
                    drainRelay()
                    runVendorWorker(id)
                    postWebhook(vendorRefOf(id))
                    inboxWorker.processDue()
                    drainRelay()

                    val seq = auditSeqOf(id)
                    seq shouldContainExactly (1..seq.size).map { it.toLong() }
                    // WORKFLOW_STARTED is the first row of the workflow, not of the application: requesting
                    // and verifying an upload happens before submit and is recorded too.
                    val types = auditTypesOf(id)
                    types.first() shouldBe "UPLOAD_REQUESTED"
                    types.indexOf("WORKFLOW_STARTED") shouldBe types.indexOf("DOCUMENT_VERIFIED") + 1
                    types.indexOf("WORKFLOW_STARTED") shouldBe
                        types.indexOfFirst { it.startsWith("VENDOR_CHECK") } - 1
                    val payloads = jdbc.queryForList(
                        "SELECT payload::text FROM audit_event WHERE application_id = ?",
                        String::class.java, id).joinToString(" ")
                    payloads shouldNotContain "Jane"
                    payloads shouldNotContain "Tan"
                    payloads shouldNotContain "1990-04-12"
                }
            }
        }

        Given("a document the vendor calls a suspected forgery") {

            When("the check completes") {
                val id = createDraft("Fraud")
                uploadId(id)
                submit(id).andExpect(status().isAccepted)
                drainRelay()
                runVendorWorker(id)
                postWebhook(vendorRefOf(id))
                inboxWorker.processDue()
                drainRelay()

                Then("FRAUD satisfies the requirement, because it is an answer") {
                    checks.findByApplicationId(id).first().outcome shouldBe IdvOutcome.FRAUD
                    requirementStatusOf(id, RequirementType.IDENTITY) shouldBe RequirementStatus.RECEIVED
                }

                Then("nothing declines: the application simply reaches CHECKS_COMPLETE") {
                    statusOf(id) shouldBe ApplicationStatus.CHECKS_COMPLETE
                }
            }
        }

        Given("an unreadable document") {

            When("the check comes back rejected") {
                val id = createDraft("Unreadable")
                uploadId(id)
                submit(id).andExpect(status().isAccepted)
                drainRelay()
                runVendorWorker(id)
                postWebhook(vendorRefOf(id))
                inboxWorker.processDue()
                drainRelay()

                Then("the applicant is asked for another, and told what is accepted") {
                    statusOf(id) shouldBe ApplicationStatus.NEEDS_INFO
                    requirementStatusOf(id, RequirementType.IDENTITY) shouldBe RequirementStatus.NEEDS_EVIDENCE

                    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/v1/applications/$id").header("X-User-Id", user))
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.requirements[0].status").value("NEEDS_EVIDENCE"))
                        .andExpect(jsonPath("$.requirements[0].acceptedDocumentKinds[0]").value("ID"))
                }

                Then("a re-upload starts a second check under a different key, not a retry") {
                    val firstKey = checks.findByApplicationId(id).first().idempotencyKey.value()

                    uploadId(id, jpegBytes(128))
                    drainRelay()

                    val all = checks.findByApplicationId(id)
                    all shouldHaveSize 2
                    all.map { it.idempotencyKey.value() }.distinct() shouldHaveSize 2
                    all.map { it.idempotencyKey.value() }.contains(firstKey) shouldBe true
                    statusOf(id) shouldBe ApplicationStatus.VERIFYING
                }
            }
        }

        Given("a vendor that never calls back") {

            When("the reconciler polls instead") {
                val id = createDraft("Tan")
                uploadId(id)
                submit(id).andExpect(status().isAccepted)
                drainRelay()
                runVendorWorker(id)

                Then("it recovers the result with no webhook at all") {
                    // No postWebhook here on purpose: a lost callback must be a delay, not a stuck application.
                    runReconciler(id)
                    drainRelay()

                    checks.findByApplicationId(id).first().status shouldBe CheckStatus.COMPLETED
                    statusOf(id) shouldBe ApplicationStatus.CHECKS_COMPLETE
                }
            }
        }

        Given("a vendor that never answers at all") {

            When("the 30-minute deadline passes with no callback and no result") {
                val id = createDraft("Tan")
                uploadId(id)
                submit(id).andExpect(status().isAccepted)
                drainRelay()
                runVendorWorker(id)

                // Moving the deadline is how the test skips the 30 minutes; the reconciler reads only the column.
                jdbc.update("UPDATE vendor_check SET deadline_at = now() - interval '1 minute', " +
                    "next_attempt_at = now() - interval '1 minute' WHERE application_id = ?", id)
                runReconciler(id)
                drainRelay()

                Then("the check fails with the reason recorded, and the requirement is UNAVAILABLE") {
                    val check = checks.findByApplicationId(id).single()
                    check.status shouldBe CheckStatus.FAILED
                    check.failureCode shouldBe "deadline-exceeded"
                    requirementStatusOf(id, RequirementType.IDENTITY) shouldBe RequirementStatus.UNAVAILABLE
                    auditTypesOf(id).count { it == "VENDOR_CHECK_FAILED" } shouldBe 1
                }

                Then("the application still reaches CHECKS_COMPLETE - an outage is a gap, never a decline") {
                    statusOf(id) shouldBe ApplicationStatus.CHECKS_COMPLETE
                }
            }
        }

        Given("Onfido creates the check but its reply never reaches us") {

            When("the worker retries") {
                // The mock creates the check and answers after 12 s; the 10 s submit timeout gives up first. Onfido
                // ignores Idempotency-Key, so a retry that simply re-posted would pay for a second check.
                val id = createDraft("Lostreply")
                uploadId(id)
                submit(id).andExpect(status().isAccepted)
                drainRelay()
                runVendorWorker(id)

                val afterTimeout = checks.findByApplicationId(id).single()

                // The backoff is seconds long; moving next_attempt_at is how the test skips the wait.
                jdbc.update("UPDATE vendor_check SET next_attempt_at = now() - interval '1 minute' WHERE id = ?",
                    afterTimeout.id)
                runVendorWorker(id) { all -> all.none { it.status == CheckStatus.RETRY } }

                Then("the first attempt kept the applicant, though the check itself timed out") {
                    afterTimeout.status shouldBe CheckStatus.RETRY
                    afterTimeout.vendorSubjectRef!!.value() shouldBe "apl_lostreply"
                }

                Then("the retry adopts the check Onfido already created instead of paying for another") {
                    // A second POST /checks would hang past the timeout too and leave the check in RETRY.
                    val check = checks.findByApplicationId(id).single()
                    check.status shouldBe CheckStatus.AWAITING_CALLBACK
                    check.vendorRef!!.value() shouldStartWith "chk_clear_lostreply_"
                    check.attempts shouldBe 2
                }
            }
        }

        Given("a webhook that does not verify") {

            When("it is delivered with a forged signature") {
                val id = createDraft("Tan")
                uploadId(id)
                submit(id)
                drainRelay()
                runVendorWorker(id)
                val ref = vendorRefOf(id)

                Then("it is refused and nothing is recorded") {
                    postWebhook(ref, signature = "00".repeat(32)).andExpect(status().isUnauthorized)

                    inbox.countByVendorAndEventId("onfido", "$ref:check.completed") shouldBe 0
                    checks.findByApplicationId(id).first().status shouldBe CheckStatus.AWAITING_CALLBACK
                }
            }

            When("the same genuine webhook is delivered twice") {
                val id = createDraft("Tan")
                uploadId(id)
                submit(id)
                drainRelay()
                runVendorWorker(id)
                val ref = vendorRefOf(id)

                Then("both are answered 200 but only one row exists") {
                    postWebhook(ref).andExpect(status().isOk)
                    postWebhook(ref).andExpect(status().isOk)

                    inbox.countByVendorAndEventId("onfido", "$ref:check.completed") shouldBe 1
                }

                Then("the requirement moves exactly once") {
                    postWebhook(ref)
                    postWebhook(ref)
                    inboxWorker.processDue()
                    drainRelay()

                    statusOf(id) shouldBe ApplicationStatus.CHECKS_COMPLETE
                    auditTypesOf(id).count { it == "VENDOR_CHECK_COMPLETED" } shouldBe 1
                }
            }
        }

        Given("an object that disagrees with what was declared") {

            When("the checksum does not match the bytes") {
                val id = createDraft("Tan")
                val bytes = jpegBytes()
                // Declare a digest of different content. MinIO refuses the PUT outright, so /complete finds
                // no object at all - the pre-signed URL is the first line of defence.
                val (_, outcome) = runCatching { uploadId(id, bytes, declaredSha = "b".repeat(64)) }
                    .getOrElse { UUID.randomUUID() to "put-or-complete-refused" }

                Then("the bytes never become evidence") {
                    documentStatusesOf(id).none { it == "UPLOADED" } shouldBe true
                }

                Then("submit is refused, naming what is missing") {
                    submit(id).andExpect(status().isConflict)
                        .andExpect(jsonPath("$.type").value("/problems/not-submittable"))
                        .andExpect(jsonPath("$.missing[0]").value("ID"))
                }
            }
        }

        Given("a submitted application") {

            When("the applicant tries to edit the declared data afterwards") {
                val id = createDraft("Tan")
                uploadId(id)
                submit(id).andExpect(status().isAccepted)

                Then("it is a conflict - the FR1 test that could not be written until now") {
                    mvc.perform(patch("/v1/applications/$id").header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"firstName":"Mallory","lastName":"Tan","dateOfBirth":"1990-04-12","country":"SG","version":2}"""))
                        .andExpect(status().isConflict)
                        .andExpect(jsonPath("$.type").value("/problems/not-editable"))
                        .andExpect(jsonPath("$.currentStatus").value("SUBMITTED"))
                }

                Then("uploading more evidence is refused too, since nothing is wanted") {
                    mvc.perform(post("/v1/applications/$id/documents").header("X-User-Id", user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"kind":"ID","contentType":"image/jpeg","sizeBytes":64,"sha256":"${"a".repeat(64)}"}"""))
                        .andExpect(status().isConflict)
                }
            }
        }

        Given("an applicant who submits without an accepted ID") {

            When("they submit with declared data only") {
                val id = createDraft("Tan")

                Then("it is refused, and no workflow starts") {
                    submit(id).andExpect(status().isConflict)
                        .andExpect(jsonPath("$.type").value("/problems/not-submittable"))
                        .andExpect(jsonPath("$.missing[0]").value("ID"))

                    statusOf(id) shouldBe ApplicationStatus.DRAFT
                    auditTypesOf(id).none { it == "WORKFLOW_STARTED" } shouldBe true
                }
            }
        }
    }
}
