package com.mettyoung.creditcardapplication.document.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;

/**
 * Creates the bucket if it is missing, which is what makes {@code bootRun} and the integration tests work
 * against a bare object store.
 * <p>
 * In production the bucket is provisioned out of band, with a retention and access policy this has no opinion
 * about — so this is idempotent and deliberately does nothing when the bucket already exists.
 */
@Component
@Slf4j
@RequiredArgsConstructor
class BucketInitializer {

    private final S3ObjectStoreAdapter store;
    private final StorageProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    void ensureBucket() {
        try {
            store.createBucketIfMissing();
            store.allowBrowserUploads(properties.corsAllowedOrigins());
        } catch (SdkException e) {
            // An unreachable store must not stop the application booting. Every other feature works without
            // it, and the specs that do not touch uploads would otherwise need a container they never use.
            // An upload against a store that is really down still fails, at the request that needs it.
            log.warn("Could not reach the object store at startup; uploads will fail until it is available: {}",
                    e.getMessage());
        }
    }
}
