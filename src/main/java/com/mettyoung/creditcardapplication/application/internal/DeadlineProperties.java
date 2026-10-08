package com.mettyoung.creditcardapplication.application.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * FR9's two deadlines. Placeholders for a product decision (NEEDS_INFO) and an operations target (REFERRED).
 *
 * @param needsInfo how long an applicant has to send what was asked before the application expires
 * @param referred  how long a referral may wait before it counts as overdue; it is never closed automatically
 */
@ConfigurationProperties(prefix = "app.deadlines")
record DeadlineProperties(Duration needsInfo, Duration referred) {
}
