# 4. The aggregate is the JPA entity

Status: Accepted

## Context

The alternative is a separate domain model mapped to a persistence model. That buys independence from the
ORM and costs a mapping layer that has to be written, tested and kept in step.

## Decision

One class. `@Getter` only, never `@Setter` or `@Data`; state changes go through domain methods that guard
the transition. A `protected` no-arg constructor for JPA, a private real one, and a static factory.

Ids are pre-assigned `UuidV7.generate()`. Being time-ordered, `ORDER BY id DESC` replaces a `created_at`
column for listing.

`@Version` is `Long`, not `long`: Spring Data reads `null` as "new" and calls `persist` instead of `merge`.

## Consequences

There is no mapping layer to drift, and the rules live where the state does.

The entity carries JPA annotations into the domain, and a separate model is the escape hatch if mapping
friction ever justifies it. It has not yet.

UUIDv7 is time-ordered *across* milliseconds but arbitrary within one, so "newest" is ordered by an explicit
timestamp where it matters, with the id only as a tiebreak.
