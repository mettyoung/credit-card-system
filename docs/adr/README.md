# Architecture decisions

One file per decision, in the format Michael Nygard proposed: the context that forced a choice, the choice,
and what it costs. They are written when the decision is made and not edited afterwards — a decision that
turns out wrong gets a new record that supersedes it, because the reasoning at the time is the part worth
keeping.

The per-increment designs in `docs/` say how something works. These say why it is that way and what the
alternative would have cost.

| # | Decision |
|---|---|
| [1](0001-modular-monolith.md) | A modular monolith, not services |
| [2](0002-postgres-is-the-only-infrastructure.md) | Postgres is the only infrastructure |
| [3](0003-modules-expose-a-facade.md) | Modules expose a facade; everything else is package-private |
| [4](0004-aggregate-is-the-jpa-entity.md) | The aggregate is the JPA entity |
| [5](0005-value-objects-validate-in-their-constructor.md) | Value objects validate in their own constructor |
| [6](0006-the-database-settles-concurrency.md) | The database settles concurrency, never check-then-act |
| [7](0007-errors-are-sealed-by-category.md) | Errors are sealed by category, not by exception class |
| [8](0008-diagrams-are-hand-maintained-mermaid.md) | The architecture diagrams are hand-maintained Mermaid |
| [9](0009-audit-writes-join-the-callers-transaction.md) | An audit write joins the caller's transaction |
| [10](0010-append-only-is-a-trigger.md) | Append-only is enforced by a trigger, not by REVOKE |
| [11](0011-bytes-bypass-the-api.md) | Uploaded bytes never pass through the API |
| [12](0012-a-rejected-document-is-a-verdict.md) | A rejected document is a 200 with a verdict |
| [13](0013-transactional-outbox.md) | A transactional outbox, not a broker |
| [14](0014-orchestration-not-choreography.md) | One component decides what happens next |
| [15](0015-a-webhook-is-a-notification.md) | A webhook is a notification, never a result |
| [16](0016-a-failure-is-recorded-never-a-decline.md) | An unanswerable check is recorded, never a decline |
