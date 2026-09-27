# 10. Append-only is enforced by a trigger, not by REVOKE

Status: Accepted

## Context

The obvious way to make a table append-only is `REVOKE UPDATE, DELETE`. It does not work here: the
application owns the table, and an owner keeps its rights whatever is revoked. "The app's role has INSERT
and SELECT only" would have been a claim the schema did not enforce.

## Decision

A `BEFORE UPDATE OR DELETE` trigger that raises. It holds regardless of who is connected.

## Consequences

The invariant is true rather than asserted, and the spec proves it by issuing raw `UPDATE` and `DELETE`
through `JdbcTemplate` — going around the mapping is the only way to attempt what the trigger refuses, and
it is what an operator with a psql session would do.

It is a guard, not the real thing. The production answer is a separate owner role that grants only `INSERT`
and `SELECT` to the application role; that costs the app ownership of its own schema and gives Flyway its
own credentials, so it is deferred and named here rather than left implied.
