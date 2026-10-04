package com.mettyoung.creditcardapplication.vendor.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param baseUrl        the mock in development, {@code https://api.eu.onfido.com} in production — switching
 *                       is this value and the token, because the mock speaks the same API
 * @param resultDeadline how long a callback is worth waiting for before the check is failed
 * @param pollAfter      how soon the reconciler first looks, in case the callback never arrives
 */
@ConfigurationProperties(prefix = "app.onfido")
record OnfidoProperties(String baseUrl, String apiVersion, String apiToken, String webhookToken,
                               Duration submitTimeout, Duration resultDeadline, Duration pollAfter,
                               int maxAttempts) {
}
