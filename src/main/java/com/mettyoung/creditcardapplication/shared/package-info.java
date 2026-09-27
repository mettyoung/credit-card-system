/**
 * What every module may use: the error category, time-ordered ids, and the transactional outbox.
 * <p>
 * Depends on nothing. The relay hands events to a {@code DomainEventListener} declared here and implemented by
 * the workflow, which is what stops the outbox knowing about orchestration.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Shared")
package com.mettyoung.creditcardapplication.shared;
