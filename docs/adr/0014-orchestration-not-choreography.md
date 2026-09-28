# 14. One component decides what happens next

Status: Accepted

## Context

With events in play, the cheap option is choreography: each worker reacts to an event and transitions the
application itself. The answer to "why is this application in this state" then lives in a graph of
listeners.

## Decision

`ApplicationProcess` is the only thing that transitions an application. Workers do one job and report back
through the outbox; nothing else may mutate a requirement on the way past.

The decision itself is `Evaluator.evaluate` — a pure function over the current state, with no I/O, no clock
and no randomness.

## Consequences

The whole decision table is a unit test rather than an integration test, which is where most of FR4's and
FR5's coverage lives.

One transaction per event: insert `processed_event`, load, apply, evaluate, apply the step, write any outbox
and audit rows, commit. An optimistic-lock conflict propagates and the relay retries from the start rather
than resolving a half-applied decision.

Mutating a requirement outside the orchestrator hides state from the evaluator and the next step never
fires. That is not a theoretical risk — it happened, and the fix was to delete the helper that did it.
