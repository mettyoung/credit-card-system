# 6. The database settles concurrency, never check-then-act

Status: Accepted

## Context

Two rules need to hold under concurrent requests: one draft per user per product, and no silent overwrite of
a newer save. Reading first and then acting is a race in both cases.

## Decision

Uniqueness is a partial unique index. The service inserts, catches the violation and translates it by
constraint name — it never checks first. `createDraft` is deliberately *not* `@Transactional`, because the
aborted transaction cannot serve the follow-up lookup.

The stale-copy precondition is a client-supplied `version` carried inside the command, checked against
`@Version`. The update runs in its own `TransactionTemplate` so a lost race is translated *after* rollback;
`@Transactional` would raise `UnexpectedRollbackException` at commit instead. Always `saveAndFlush`, so a
conflict surfaces inside the method rather than at commit.

## Consequences

Both rules hold under genuine concurrency, which is why the specs run overlapping requests rather than
sequential ones — a test that does not overlap exercises neither the index nor the lock.

The transaction boundaries are explicit and slightly unusual, so each one carries a comment saying why the
obvious annotation would be wrong.
