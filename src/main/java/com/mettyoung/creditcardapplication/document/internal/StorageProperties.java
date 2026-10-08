package com.mettyoung.creditcardapplication.document.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * @param uploadUrlValidFor how long a pre-signed URL stays usable; short, because it is a bearer token
 *                          for a write to our bucket
 */
@ConfigurationProperties(prefix = "app.storage")
record StorageProperties(String endpoint, String region, String bucket, String accessKey,
                                String secretKey, Duration uploadUrlValidFor, List<String> corsAllowedOrigins) {
}
