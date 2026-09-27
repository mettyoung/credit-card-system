package com.mettyoung.creditcardapplication.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * One injected clock, so anything with a deadline or a timestamp can be tested by moving time rather than
 * by sleeping. Value objects still read {@code LocalDate.now()} directly: their rules are monotonic, so a
 * clock would be ceremony.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
