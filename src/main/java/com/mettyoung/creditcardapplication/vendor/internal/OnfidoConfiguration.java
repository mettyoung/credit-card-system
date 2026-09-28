package com.mettyoung.creditcardapplication.vendor.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The properties are a record, so they need enabling somewhere the adapter's package can see. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OnfidoProperties.class)
class OnfidoConfiguration {
}
