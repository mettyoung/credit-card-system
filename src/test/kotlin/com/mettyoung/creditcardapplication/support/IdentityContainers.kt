package com.mettyoung.creditcardapplication.support

import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Paths

/**
 * The three containers FR5 needs, started once for the whole run.
 *
 * MinIO and WireMock are plain [GenericContainer]s: Testcontainers 2.x has no module for either, and neither
 * needs more than an image, a port and a wait strategy.
 *
 * The Onfido stubs are mounted from the project's own `mock/onfido`, the same directory `compose.yaml` mounts,
 * so a stub fixed for a test is fixed for local development and the two cannot drift.
 */
object IdentityContainers {

    val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

    /**
     * LocalStack's S3 rather than MinIO. The design names MinIO, and nothing in the application knows the
     * difference — that is what the `ObjectStore` port is for — but MinIO's community image is not pullable
     * from this environment, and a test that cannot run is worth less than an equivalent one that can.
     * Both speak the S3 API, including pre-signed PUTs and SHA-256 checksums, which is all this exercises.
     */
    val objectStore: GenericContainer<*> = GenericContainer("localstack/localstack:3")
        .withExposedPorts(4566)
        .withEnv("SERVICES", "s3")
        .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566))

    val onfido: GenericContainer<*> = GenericContainer("wiremock/wiremock:3.13.2-alpine")
        .withExposedPorts(8080)
        .withCopyFileToContainer(
            MountableFile.forHostPath(Paths.get("mock/onfido/mappings").toAbsolutePath()),
            "/home/wiremock/mappings")
        .withCommand("--verbose", "--global-response-templating")
        .waitingFor(Wait.forHttp("/__admin/mappings").forPort(8080))

    private var started = false

    /** Starts everything and points the application at it. Idempotent, so every spec may call it. */
    @Synchronized
    fun start() {
        if (started || !dockerIsAvailable()) {
            return
        }
        listOf(postgres, objectStore, onfido).forEach { it.start() }

        // Set before Spring builds a context: the properties are read at bean creation.
        System.setProperty("app.storage.endpoint",
            "http://${objectStore.host}:${objectStore.getMappedPort(4566)}")
        System.setProperty("app.onfido.base-url", "http://${onfido.host}:${onfido.getMappedPort(8080)}")
        started = true
    }

    fun onfidoAdminUrl(): String = "http://${onfido.host}:${onfido.getMappedPort(8080)}/__admin"
}

