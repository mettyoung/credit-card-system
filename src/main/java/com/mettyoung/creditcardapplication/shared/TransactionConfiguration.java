package com.mettyoung.creditcardapplication.shared;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One shared {@link TransactionTemplate}, because several classes need a transaction they open and close
 * themselves rather than through {@code @Transactional} — a lost optimistic-lock race has to be translated
 * after the rollback, and the proxy would raise {@code UnexpectedRollbackException} at commit instead.
 * <p>
 * Boot does not auto-configure one. Each of those classes used to build its own, identically, which forced a
 * hand-written constructor on an otherwise plain bean. A template with default settings holds no per-caller
 * state and is thread-safe once built, so one instance serves all of them.
 * <p>
 * A caller that needs different propagation or isolation builds its own rather than mutating this.
 */
@Configuration(proxyBeanMethods = false)
class TransactionConfiguration {

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
