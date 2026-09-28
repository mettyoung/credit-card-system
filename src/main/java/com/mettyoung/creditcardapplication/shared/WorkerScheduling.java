package com.mettyoung.creditcardapplication.shared;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduled workers. Today that is the upload sweep; later increments add their own, and each one
 * is a {@code @Scheduled} bean rather than a separate process.
 * <p>
 * Behind a property so the API specs can turn the timers off and drive each worker by hand. That is not a
 * convenience: with the scheduler running, an assertion about how many times something happened races a
 * background poll, and the test would pass or fail on timing rather than on behaviour.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.workers.enabled", matchIfMissing = true)
@EnableScheduling
class WorkerScheduling {
}
