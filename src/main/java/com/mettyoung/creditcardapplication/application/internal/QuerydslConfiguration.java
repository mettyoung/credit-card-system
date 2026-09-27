package com.mettyoung.creditcardapplication.application.internal;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Querydsl has no Boot auto-configuration, so the factory is an explicit bean.
 * <p>
 * It is built from the shared {@code EntityManager} proxy, which resolves to the one bound to the current
 * transaction — so a query sees the persistence context of whatever called it.
 */
@Configuration(proxyBeanMethods = false)
class QuerydslConfiguration {

    @Bean
    JPAQueryFactory jpaQueryFactory(EntityManager entityManager) {
        return new JPAQueryFactory(entityManager);
    }
}
