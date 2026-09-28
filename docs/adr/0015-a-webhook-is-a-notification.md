# 15. A webhook is a notification, never a result

Status: Accepted

## Context

The IDV vendor calls back when a check completes, and the callback carries the outcome. Trusting that body
makes correctness depend on a request we do not control, that may be lost, duplicated, delayed or forged.

## Decision

The body is treated as "something is ready" and nothing more. The handler verifies the HMAC over the *raw*
bytes, inserts an inbox row, and answers 200 fast. A worker then fetches the result from the vendor over an
authenticated call.

A reconciler polling a deadline column reaches the same answer with no callback at all. Both paths race on
one conditional update, and the loser updates zero rows.

## Consequences

A lost webhook costs latency, not correctness. A duplicate has one effect, because the inbox is unique on
the vendor's resource and action. A forged one fails signature verification before anything is parsed.

Re-serialising the body before verifying would reorder keys and break a genuine callback, so the raw bytes
are what is signed and what is checked.

Onfido signs the body alone with no timestamp header, so a captured request stays valid indefinitely. That
is the vendor's contract, recorded here because it cannot be fixed on our side.
