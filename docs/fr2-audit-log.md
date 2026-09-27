# FR2 — Audit Log

Parent: [Intake and Checks](credit-card-application-design-spec.md). Next: [FR3 — Upload KYC Documents](fr3-document-upload.md).
Spring Boot 4.1, Java 21, Spring Data JPA, PostgreSQL, Flyway.

An append-only record of what happened to an application. Its own increment because **every increment from FR3 onward
depends on it and none of them owns it**: `DocumentService`, `ApplicationService`, `ApplicationProcess` and all three
vendor workers take an `AuditTrail` in their constructors, so none of them can be built or started without this.

It is the first thing in the codebase that is infrastructure rather than a feature, and it sets the pattern the outbox
follows in FR4: a facility declared once, written to by everyone, depending on nothing.

## 1. Requirements

| #     | Functional                                                                                                        |
|-------|-------------------------------------------------------------------------------------------------------------------|
| FR2.1 | Append a typed, timestamped record against an application, in the same transaction as the change it describes.      |
| FR2.2 | Refuse to record outside a transaction, rather than committing a row independently of that change.                  |
| FR2.3 | Number records per application so that a missing one is visible.                                                    |
| FR2.4 | Prevent the application that writes the log from editing or deleting it.                                            |

Out of scope: reading the log. Nothing in this scope exposes it over HTTP, and no endpoint returns audit rows — the tests
read it through the repository. An audit trail nobody can query is half a feature, and that half is deliberate: who may
read it is an access-control question this scope has no answer for.

| Quality      | Requirement                                                                                                  |
|--------------|--------------------------------------------------------------------------------------------------------------|
| Atomicity    | A change that committed is a change that was logged. Never one without the other.                             |
| Immutability | Append-only, enforced by the database rather than by convention.                                              |
| Completeness | `seq` is contiguous per application, so a gap is evidence of a problem rather than invisible.                  |
| Privacy      | Ids, codes, statuses and versions only. No declared data, no vendor message, no document content.              |
| Independence | Depends on no feature module, so it can never be part of a dependency cycle with its writers.                 |

## 2. Core Entity

| Entity         | Fields                                                                            | Invariants                                    |
|----------------|-----------------------------------------------------------------------------------|-----------------------------------------------|
| **AuditEvent** | `id`, `applicationId`, `seq`, `type`, `actor`, `actorId?`, `payload` jsonb, `at`    | Append-only. `seq` contiguous per application. |

| #   | Invariant                                 | Enforced by                                  |
|-----|-------------------------------------------|----------------------------------------------|
| I9  | Rows are never updated or deleted          | A `BEFORE UPDATE OR DELETE` trigger           |
| I10 | `seq` is gap-visible per application       | `ux_audit_app_seq`, plus the optimistic lock   |

`actor` is `APPLICANT` or `SYSTEM`, with `actorId` carrying the user id for the former. There is no `REVIEWER` yet — that
arrives with the deferred manual-review increment, and adding a constant is not a migration because the column is `text`.

## 3. API

```java
public interface AuditTrail {
    void record(UUID applicationId, AuditEventType type, Actor actor, String actorId, Map<String, Object> payload);
    void recordSystem(UUID applicationId, AuditEventType type, Map<String, Object> payload);
}
```

That is the whole surface. `AuditEvent`, its repository and its sequence stay inside the module.

**The signature takes a `Map`, not another module's types**, and that is not laziness. `allowedDependencies = { "shared" }`
means this module may not import `application`, `document` or `vendor` — so the signature *cannot* acquire a dependency
that would put the log in a cycle with the things writing to it. A richer, typed API would have to give that up.

The cost is real and worth naming: nothing checks that a payload key is spelled the same way twice. That trade is taken
because a cycle is worse than a typo.

## 4. Design

### 4.1 Writes share the caller's transaction, and that is enforced

`AuditTrail` is `@Transactional(propagation = MANDATORY)`.

Leaving the annotation off would **not** make it non-transactional. `SimpleJpaRepository.save` is transactional itself, so
a call with no ambient transaction would open one and commit the audit row *independently of the change it describes* —
silently, which is the one failure this module exists to prevent. `MANDATORY` turns that into an
`IllegalTransactionStateException` at the first such call.

This is FR2.2, and it is the reason the module is worth its own increment: the guarantee is a property of *how* it is
called, so it has to be settled before anything calls it.

### 4.2 `seq` without a race

`seq` is `max + 1` per application.

That read-then-write looks unsafe and is not, because every audit write happens in a transaction that also touches the
application row and therefore holds its optimistic lock — so two writers for one application are already serialised. The
unique index is the backstop that turns a mistake into a failed transaction rather than a duplicate.

The dependency is worth stating plainly: **a future caller that audits without touching the application must take
`SELECT … FOR UPDATE` on it first.** Nothing enforces that today.

### 4.3 Append-only is a trigger, not a `REVOKE`

The obvious implementation is `REVOKE UPDATE, DELETE ON audit_event`. It does not work: the application owns the table, and
an owner keeps its rights whatever is revoked. "The app's role has `INSERT` and `SELECT` only" would have been a claim the
schema did not enforce.

`V2__audit_event.sql` uses a `BEFORE UPDATE OR DELETE` trigger that raises, which holds regardless of who is connected.

The production answer is a separate owner role that creates the table and grants only `INSERT, SELECT` to the application
role. That costs the app ownership of its own schema and gives Flyway its own credentials, so it is deferred — **this is a
guard, not the real thing**, and the difference matters if anyone relies on it for compliance.

### 4.4 A leaf, deliberately

```
audit/
├── AuditTrail.java       the only entrance
├── AuditEventType.java   one constant per recorded fact
├── Actor.java            APPLICANT | SYSTEM
├── domain/               AuditEvent
├── persistence/          AuditEventRepository
└── service/              AuditLog (implements AuditTrail, package-private)
```

`@ApplicationModule(allowedDependencies = { "shared" })`. Everything writes to this module and it writes to nothing, which
is what keeps it out of every cycle — an audit log that reached back into its writers would be in a cycle with all of them
at once. Spring Modulith enforces it rather than a comment asserting it.

## 5. Event types

One constant per recorded fact. Later increments add constants and change nothing else, which is the test of whether this
module was designed at the right level:

| Increment | Adds                                                                                        |
|-----------|---------------------------------------------------------------------------------------------|
| FR3       | `UPLOAD_REQUESTED`, `DOCUMENT_VERIFIED`                                                      |
| FR4       | `WORKFLOW_STARTED`, `STATUS_CHANGED`, `REQUIREMENT_CHANGED`                                   |
| FR5       | `VENDOR_CHECK_QUEUED`, `VENDOR_CHECK_COMPLETED`, `VENDOR_CHECK_FAILED`, `WEBHOOK_RECEIVED`    |

`WORKFLOW_STARTED` is the first row of the **workflow**, not of the application: uploading happens in FR3, before submit,
so `UPLOAD_REQUESTED` comes first.

## 6. Migration

`V2__audit_event.sql` — `audit_event`, `ux_audit_app_seq` (**I10**), and the trigger of **I9**.

`payload` is `jsonb`, mapped with `@JdbcTypeCode(SqlTypes.JSON)`. Without the annotation Hibernate validates a bare
`String` against `varchar` and the context fails to start — a real failure found by running it, not by reading it.

## 7. Tests

**This module has no spec of its own** — the one real gap in the increment.

Every assertion about it lives in [FR5's API spec](fr5-identity-verification.md): that `seq` is contiguous, that no
declared value appears in any payload, that `WORKFLOW_STARTED` follows the document events. Those run against real
Postgres, so the mapping and the sequence are genuinely exercised.

What is **not** exercised is the append-only trigger. Nothing attempts an `UPDATE` or a `DELETE` and asserts it is refused,
which means I9 — the invariant with the most compliance weight — is the least tested thing here. A small spec issuing a
raw `UPDATE` through the repository's `EntityManager` and expecting an exception would close it.

## 8. Open questions

- **Nobody can read the log.** No endpoint exposes it, and the first real consumer is the deferred reviewer UI. Whoever
  builds that has to answer who may read an audit trail, which is why it is not answered here.
- **Payload keys are unchecked.** §3 explains the trade; a shared constants class would help without reintroducing a
  dependency.
- **Retention.** Nothing deletes audit rows, ever. That is usually correct for an audit log and it is still a decision
  someone should make explicitly rather than inherit.
- **The trigger is not the production answer** (§4.3). Anyone treating append-only as a compliance control should read that
  section before relying on it.
