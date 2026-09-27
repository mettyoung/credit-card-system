# 12. A rejected document is a 200 with a verdict, not a 4xx

Status: Accepted

## Context

`complete` on an object whose bytes disagree with the declaration could answer 422. The instinct is that
something was wrong, so the status should say so.

## Decision

`200 {status: INVALID, reason}`. A status code describes what happened to the *request*; the body describes
what happened in the domain.

## Consequences

The request was well formed, the server reached a definite answer, and nothing failed — so none of the 4xx
or 5xx meanings apply. `INVALID` is also durable: it is persisted, audited and visible on the next `GET`,
which an error response would not be.

Retry semantics stay honest. The verdict is one-way, so a replay returns the same answer; a 5xx would have
advertised a retry that can never succeed, and would have paged someone for a badly photographed passport.

It is consistent with how the system treats worse news one layer up: a FRAUD verdict from the vendor is a
successful check, not an error.

The cost is real — a client that checks only the status code will read `INVALID` as success — so `status` is
part of the contract and the specs assert the exact body.
