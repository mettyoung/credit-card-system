package com.mettyoung.creditcardapplication.shared.outbox;

/**
 * What the relay hands a committed event to.
 * <p>
 * Declared here and implemented by the module that owns the workflow, so the outbox knows nothing about
 * orchestration. Without this inversion `shared` would depend on `application`, which depends on `shared` —
 * a cycle, and the reason the relay cannot simply call the orchestrator by name.
 */
public interface DomainEventListener {

    /** Runs inside the relay's transaction, so any state change commits with the event's completion. */
    void on(DomainEvent event);
}
