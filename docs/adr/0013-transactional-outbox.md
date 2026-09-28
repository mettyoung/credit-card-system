# 13. A transactional outbox, not a broker

Status: Accepted

## Context

Submit must record a transition and publish an event. Doing both against separate systems leaves a window
where one exists without the other, in whichever order they are attempted.

## Decision

The event is a row in the same database, written in the same transaction as the change. A relay polls
committed rows and dispatches them in process; `processed_event` dedupes the consumer. The relay claims with
`FOR UPDATE SKIP LOCKED`, so several instances can poll without coordination.

It uses a programmatic `TransactionTemplate` for the same reason the application service does: a lost
optimistic-lock race has to be caught after the rollback. On a conflict the row stays unpublished and the
next poll retries it from the start — that is the entire recovery strategy.

## Consequences

There is no lost transition and no lost event, and no second system that can disagree with the first.

Delivery is at-least-once, which is the only thing a poller can offer, so consumers must be idempotent —
hence `processed_event`.

Event payloads carry ids only. They are a durable log read by code that may be a version behind, so a name
or a date of birth in there would be both a privacy leak and a compatibility problem.
