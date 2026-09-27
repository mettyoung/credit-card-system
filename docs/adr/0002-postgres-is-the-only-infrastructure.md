# 2. Postgres is the only infrastructure

Status: Accepted

## Context

The workflow needs durable events, a job queue, a webhook inbox and a set of timers. The usual answers are
a message broker and a workflow engine.

Both are a second system of record. A broker can hold an event whose state change was rolled back; a
workflow engine holds a copy of the process state that has to agree with the application's own.

## Decision

The outbox, the job queue and the webhook inbox are Postgres tables. Every timer is a column plus a poller
(`next_attempt_at`, `lease_until`, `deadline_at`, `created_at`), never an in-memory schedule.

## Consequences

A state change and its event commit together, so durability comes from the transaction rather than from a
second system that can disagree with the first.

A restart loses nothing, because nothing that matters is held in memory.

Throughput is bounded by the database, and the pollers add latency a broker would not. Both are acceptable
at this scale, and `FOR UPDATE SKIP LOCKED` means scaling out to several instances needs no change to the
queue design.

The cost is that we implement delivery semantics ourselves: at-least-once, deduped by `processed_event`.
