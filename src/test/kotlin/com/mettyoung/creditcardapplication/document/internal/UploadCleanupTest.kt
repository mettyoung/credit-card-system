package com.mettyoung.creditcardapplication.document.internal

import com.mettyoung.creditcardapplication.document.DocumentKind
import com.mettyoung.creditcardapplication.document.DocumentStatus
import com.mettyoung.creditcardapplication.document.RequestUploadCommand
import com.mettyoung.creditcardapplication.document.ContentType
import com.mettyoung.creditcardapplication.document.Sha256
import com.mettyoung.creditcardapplication.support.DockerAvailable
import com.mettyoung.creditcardapplication.support.dockerIsAvailable
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.optional.shouldBeEmpty
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The sweep against a real Postgres and a real object store, because everything worth asserting about it is
 * an interaction with one or the other: which rows the query selects, and whether the bytes are actually gone.
 *
 * The schedulers are off, so the sweep runs only when a test calls it — otherwise a background pass would
 * decide the outcome and the assertions would be about timing.
 */
@SpringBootTest(properties = ["app.workers.enabled=false"])
@EnabledIf(DockerAvailable::class)
class UploadCleanupTest : DescribeSpec() {

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        /**
         * LocalStack's S3. The application cannot tell it from MinIO or from AWS — that is what the
         * ObjectStore is for — and LocalStack is the one that pulls in this environment.
         */
        val objectStore: GenericContainer<*> = GenericContainer("localstack/localstack:3")
            .withExposedPorts(4566)
            .withEnv("SERVICES", "s3")
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566))

        init {
            if (dockerIsAvailable()) {
                postgres.start()
                objectStore.start()
                // Set before Spring builds a context: the endpoint is read when the S3 client bean is created.
                System.setProperty("app.storage.endpoint",
                    "http://${objectStore.host}:${objectStore.getMappedPort(4566)}")
            }
        }
    }

    @Autowired
    private lateinit var cleanup: UploadCleanup

    @Autowired
    private lateinit var repository: DocumentRepository

    @Autowired
    private lateinit var store: ObjectStore

    @Autowired
    private lateinit var storage: StorageProperties

    override fun isolationMode() = IsolationMode.InstancePerTest

    private val applicationId: UUID = UUID.randomUUID()

    init {
        extension(SpringExtension())

        // A real JPEG head, so the bytes are something the rest of the module would also accept.
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(1021)
        val rawDigest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sha256 = Sha256.of(rawDigest)
        val base64Digest = Base64.getEncoder().encodeToString(rawDigest)

        fun document(createdAt: Instant): Document {
            val request = RequestUploadCommand(DocumentKind.ID, ContentType.JPEG, bytes.size.toLong(), sha256)
            val document = Document.requestUpload(Document.Owner(applicationId, "u_1"), request, createdAt)
            return repository.saveAndFlush(document)
        }

        /** Uploads through the pre-signed URL, the same way a client would — no test-only write path. */
        fun upload(document: Document) {
            val presigned = store.presignUpload(document.objectKey,
                ObjectStore.Constraints(ContentType.JPEG, bytes.size.toLong(), base64Digest),
                storage.uploadUrlValidFor())

            var request = HttpRequest.newBuilder(URI.create(presigned.url().toString()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
            presigned.requiredHeaders().forEach { (name, value) -> request = request.header(name, value) }

            val response = HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.discarding())
            response.statusCode() shouldBe 200
        }

        val longAgo = Instant.now().minus(Duration.ofHours(2))

        describe("an upload URL that was issued and never used") {

            it("expires the row and returns how many it swept") {
                val abandoned = document(longAgo)

                cleanup.sweep() shouldBe 1

                repository.findById(abandoned.id).shouldBePresent().status shouldBe DocumentStatus.EXPIRED
            }

            it("does not fail on the object that was never created") {
                // The common case: the client asked for a URL and never PUT anything, so there is nothing in
                // the bucket to delete. S3 answers 204 for a key that is not there, which is what makes the
                // sweep safe to repeat.
                document(longAgo)

                cleanup.sweep() shouldBe 1
                cleanup.sweep() shouldBe 0
            }
        }

        describe("an upload that did land but was never completed") {

            it("deletes the bytes as well as expiring the row") {
                val abandoned = document(longAgo)
                upload(abandoned)
                store.head(abandoned.objectKey).shouldBePresent()

                cleanup.sweep() shouldBe 1

                store.head(abandoned.objectKey).shouldBeEmpty()
                repository.findById(abandoned.id).shouldBePresent().status shouldBe DocumentStatus.EXPIRED
            }
        }

        describe("what the sweep must not touch") {

            it("leaves a document still inside its hour alone") {
                val fresh = document(Instant.now())

                cleanup.sweep() shouldBe 0

                repository.findById(fresh.id).shouldBePresent()
                    .status shouldBe DocumentStatus.PENDING_UPLOAD
            }

            it("leaves an old document that already has a verdict, and its bytes") {
                val verified = document(longAgo)
                upload(verified)
                verified.markUploaded()
                repository.saveAndFlush(verified)

                cleanup.sweep() shouldBe 0

                store.head(verified.objectKey).shouldBePresent()
                repository.findById(verified.id).shouldBePresent().status shouldBe DocumentStatus.UPLOADED
            }
        }
    }
}
