# 9. An audit write joins the caller's transaction

Status: Accepted

## Context

A log entry that survives a rolled-back change is a lie about what happened. The obvious guard is to leave
the write un-annotated — but `SimpleJpaRepository.save` is itself transactional, so a caller with no ambient
transaction would quietly get its own, and commit an entry describing a change that never happened.

`REQUIRES_NEW` makes that the explicit behaviour. `REQUIRED` makes it the silent one.

## Decision

`Audits.record` is `@Transactional(propagation = MANDATORY)`. There is no way to append except inside the
transaction that makes the change being recorded.

`seq` is `max + 1` per application, backstopped by a unique index.

## Consequences

Calling it outside a transaction is an exception at the first such call, not a subtly wrong log discovered
later.

Every writer must already be in a transaction, which is true of every caller today and is the constraint the
module means to impose.

The read-then-write on `seq` is safe only because callers hold the application row's lock. The index is what
turns a mistake into a failed transaction rather than a duplicate, and the spec measures that directly:
without the lock, most concurrent writers lose.
