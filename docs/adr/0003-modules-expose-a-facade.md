# 3. Modules expose a facade; everything else is package-private

Status: Accepted

## Context

Layer-per-subpackage (`web`, `service`, `domain`, `persistence`) forces types to be `public` merely to cross
a layer, because Java's package-private does not span sub-packages. `public` then means nothing: a module's
entity and its API look identical from outside.

## Decision

Each module exposes a small surface in its root package — a facade interface plus the commands, responses
and enums those signatures name — and keeps everything else package-private in a single `internal` package.

The facade answers with a response type, never the aggregate, so the entity cannot leave.

Refusals cross the boundary as `DomainException` carrying a `Category`. The exception classes themselves
are internal, because the category is the contract.

## Consequences

The compiler now refuses what previously only a test would catch, and `public` recovers its meaning: it
marks the module's API and nothing else.

The layers stop being package names and become a rule about what each class may do, enforced in review.
For `audit` (3 classes) that is free; for `application` (35) it is a real loss, paid for by the
encapsulation.

Specs that read another module's rows do so as rows, through `JdbcTemplate`, rather than by injecting its
repository.
