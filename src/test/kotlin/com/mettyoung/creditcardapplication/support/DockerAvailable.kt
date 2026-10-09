package com.mettyoung.creditcardapplication.support

import io.kotest.core.annotation.Condition
import io.kotest.core.spec.Spec
import org.testcontainers.DockerClientFactory
import kotlin.reflect.KClass

/**
 * Skips a spec when Docker is missing, so a machine without it still gets a green build from the unit specs.
 *
 * Shared rather than copied per spec: every container-backed spec needs the same answer, and two copies would
 * be two places to change when the answer does.
 */
class DockerAvailable : Condition {
    override fun evaluate(kclass: KClass<out Spec>) = dockerIsAvailable()
}

/**
 * Whether Docker is reachable. With `REQUIRE_DOCKER=true` (CI sets it) a missing Docker fails the build instead: a
 * green build that silently skipped every integration spec is the one result CI must never report.
 */
fun dockerIsAvailable(): Boolean {
    val available = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
    check(available || System.getenv("REQUIRE_DOCKER") != "true") {
        "REQUIRE_DOCKER is set but Docker is not available; the integration specs would be skipped"
    }
    return available
}
