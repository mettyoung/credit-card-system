package com.mettyoung.creditcardapplication.document.internal

import com.mettyoung.creditcardapplication.support.DockerAvailable
import com.mettyoung.creditcardapplication.support.IdentityContainers
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import software.amazon.awssdk.services.s3.S3Client

/**
 * FR10: a browser PUTs to a pre-signed URL on the store's origin, so the bucket must allow exactly that - PUT,
 * from the app's origin, with the two headers the URL is signed with - and nothing more.
 */
@SpringBootTest(properties = ["app.workers.enabled=false"])
@EnabledIf(DockerAvailable::class)
class BucketCorsTest : DescribeSpec() {

    companion object {
        @JvmStatic
        @ServiceConnection
        val postgres = IdentityContainers.postgres

        init {
            IdentityContainers.start()
        }
    }

    @Autowired private lateinit var s3: S3Client
    @Autowired private lateinit var storage: StorageProperties

    init {
        extension(SpringExtension())

        describe("the bucket, once the app is ready") {

            it("lets the web UI's origin PUT, and allows nothing else") {
                val rules = s3.getBucketCors { it.bucket(storage.bucket()) }.corsRules()

                rules shouldHaveSize 1
                val rule = rules.single()
                rule.allowedOrigins() shouldContainExactly listOf("http://localhost:8080")
                rule.allowedMethods() shouldContainExactly listOf("PUT")
                // A wildcard, because LocalStack mis-splits a browser's "a,b" header list (see allowBrowserUploads).
                rule.allowedHeaders() shouldContainExactly listOf("*")
            }
        }
    }
}
