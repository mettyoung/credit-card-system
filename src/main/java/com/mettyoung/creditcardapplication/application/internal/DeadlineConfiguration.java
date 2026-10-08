package com.mettyoung.creditcardapplication.application.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The properties are a record, so they need enabling somewhere their package can see. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeadlineProperties.class)
class DeadlineConfiguration {
}
