# CreditCardApplication

Spring Boot 4.1 / Java 21 backend. PostgreSQL + Flyway, Hibernate, MapStruct, Lombok. **Main sources Java, tests
Kotlin + Kotest.** (Global KMP/Compose prefs don't apply — JVM backend, no UI.)

`README.md` is the intake + checks scope (FR1–FR8; FR6 and the remaining declared data are outside this project — its Appendix A); `docs/` holds one tech design per increment (`fr1-` … `fr5-`). Read the relevant one before changing behaviour — the
FR/invariant ids in code comments come from there. `docs/providers/` has one integration spec per external provider
(calls, vendor→internal mapping, failure taxonomy, fake-vendor triggers); read the provider's spec before touching its adapter.

The C4 model lives in `docs/c4-model.html` (Mermaid, rendered in the browser). Change behaviour that moves a box or an arrow, and the diagram
changes in the same commit.

## Commands

```bash
./gradlew build     # compile + test
./gradlew bootRun   # docker-compose brings up Postgres, the object store and the Onfido mock
```

Integration tests self-skip without Docker (`@EnabledIf`), so a green build without Docker means less than it looks. FR5
needs three containers: Postgres, an S3-compatible store, and the Onfido mock (`mock/onfido`, mounted by both
`compose.yaml` and the specs so they cannot drift). The API specs set `app.workers.enabled=false` and drive each worker by
hand — with the scheduler running, an assertion like "exactly one `POST /checks`" would pass or fail on timing.

## Layout

Package-per-feature, layer-per-subpackage. Features: `application` (+ `process/` for the orchestrator), `document`,
`vendor` (+ `onfido/` for the adapter), `audit`, plus root `shared/` (`DomainException`, `UuidV7`, `outbox/`). New
features go in as siblings, not as root-level layers.

**Spring Modulith enforces the boundaries** (`ModuleStructureTest`): each module declares its allowed dependencies in
`package-info.java`, and a module may only use types another module **exposed** — its root package or a
`@NamedInterface`. So a module's API lives in its root (`Documents`, `UploadPort`, `IdentityChecks`, `ApplicantSubjects`,
`AuditTrail`) and its entities, repositories and adapters stay in sub-packages. Reaching for another module's repository
now fails the build, which is how three dependency cycles were found.

When two modules must talk both ways, **declare the port in the module being called and implement it in the caller**
(`ApplicantSubjects` in `vendor`, implemented in `application`; `DomainEventListener` in `shared/outbox`, implemented by
the orchestrator). Adding a dependency to `allowedDependencies` to make a cycle compile is the wrong fix.

| Layer     | Owns                                                                      | Must not                                |
|-----------|---------------------------------------------------------------------------|-----------------------------------------|
| `web`     | Header/DTO parsing, status mapping, problem JSON                          | Business rules; touching the repository |
| `service` | Load-by-owner, version precondition, tx boundaries, exception translation | Field validation                        |
| `domain`  | State guards; value objects validate their own fields                     | Know about HTTP or JPA exceptions       |
| `process` | Deciding the next step; applying it in one transaction                     | Calling a vendor, or holding a tx open over one |
| `worker`  | Claiming work, calling out, recording the result                           | Transitioning an application            |
| DB        | Uniqueness under concurrency, optimistic lock, append-only audit          | —                                       |

## Patterns

**Aggregate = JPA entity.** `@Getter` only, never `@Setter`/`@Data`; state changes via domain methods. `protected`
no-arg ctor for JPA, private real ctor, static factory. Ids are pre-assigned `UuidV7.generate()` — time-ordered, so
`ORDER BY id DESC` replaces a `created_at` column. `@Version` is `Long`, not `long`: Spring Data reads `null` as "new"
and calls `persist` instead of `merge`.

**Value objects validate in their constructor**, so every instance is valid (`FirstName`, `DateOfBirth`, `Country`). Persist via
`AttributeConverter` that routes loads back through the constructor, so a bad row fails on load. Converters are
**package-private** — they are named only by the `@Convert` on the entity beside them, so `public` in a `domain`
package keeps its meaning: this type crosses a layer. DTO fields stay
unannotated on purpose (`UpdateDraftRequest.firstName`) — the rules live in the value object and can't be bypassed. A
multi-field save builds every value object before assigning any, so a rejection can't half-apply. Bean
Validation on DTOs is shape only (`@NotNull`).

**Errors are sealed by category, not exception class.** Domain errors extend `DomainException` and name a `Category`:
`INVALID_VALUE`→422, `CONFLICTING_STATE`→409, `NOT_FOUND`→404. `ApiExceptionHandler.domain` switches exhaustively, so a
new category breaks the build while a new exception of an existing category needs no web change. Problem `type` from
`code()`, extra members from `details()`.

- No request values or PII in messages/details — describe the rule.
- Not-found and not-yours are one outcome (`findByIdAndUserId`), so ids can't be probed. A malformed path UUID also
  answers 404.
- All errors are RFC 9457 via `ProblemMapper`; the handler is `@Order(HIGHEST_PRECEDENCE)` to beat Boot's own advice.

**Workers claim, call, then record — three transactions, never one.** A vendor call happens *outside* any transaction:
holding one open across a third-party call turns a slow vendor into a connection-pool outage. The claim commits first, so
a crashed worker looks exactly like an expired lease and the claim query picks it up again.

**One component decides.** `ApplicationProcess` is the only thing that transitions an application; workers do one job and
report back through the outbox. The decision itself is `Evaluator.evaluate` — pure, no I/O, no clock — which is why most
of FR4's and FR5's coverage is a unit test. Nothing else may mutate a requirement on the way past: doing so hides state from the
evaluator, and the next step never fires.

**A webhook is a notification, never a result.** Verify the HMAC over the *raw* bytes (re-serialising reorders keys and
breaks a genuine callback), record that an answer exists, answer 200 fast, then fetch the answer by the vendor's id in a
worker. That is what makes a lost, duplicated or forged callback harmless — and the reconciler makes the callback an
optimisation rather than a requirement.

**Every timer is a column plus a poller**, never an in-memory schedule: `next_attempt_at`, `lease_until`, `deadline_at`,
`created_at`. A restart loses nothing.

**The database settles concurrency, never check-then-act.**

- Uniqueness: insert, then translate the violation by constraint name (`ux_application_one_draft`). `createDraft` is
  deliberately *not* `@Transactional` — the aborted tx can't serve the follow-up lookup.
- Optimistic lock: `updateDeclaredData` uses its own `TransactionTemplate` so a lost race is translated *after* rollback;
  `@Transactional` would throw `UnexpectedRollbackException` at commit instead. Always `saveAndFlush` so conflicts
  surface in the method.
- Client-supplied `version` in the body is the stale-copy precondition (no ETag/If-Match).

**Flyway owns the schema** (`db/migration/V<n>__*.sql`); `ddl-auto=validate` only checks mapping. Add a migration, never
let Hibernate alter anything. Partial unique indexes carry invariants the code can't.

**MapStruct for entity→response** (`ApplicationResponse.Mapper`, nested, `componentModel = SPRING`).
`-Amapstruct.unmappedTargetPolicy=ERROR` means a new response field with no source fails compilation. Lombok's processor
must stay ordered before MapStruct's, with `lombok-mapstruct-binding` between.

**Auth is a placeholder.** `UserId` record resolved from `X-User-Id` by a Spring `Converter`; blank returns `null` so
blank and absent both give 401. Services take `String userId` — nothing below `web` knows about the header.

Controller methods and web-internal classes are package-private.

**`public` inside a module means "another layer of this module needs it", not "API".** Java's package-private does not
span sub-packages, so anything crossing `domain` → `service` → `web` → `process` has to be `public` whether or not it is
meant for the outside. Modulith supplies the visibility Java lacks: a `domain` package is neither a module root nor a
`@NamedInterface`, so another module importing from it fails `verify()` even though the compiler allows it. Keep a type
package-private whenever it genuinely does not leave its package (`NameRules`, every `AttributeConverter`) — that is what
stops `public` becoming noise.

## Tests

Kotlin + Kotest on the JUnit platform. No mocking library anywhere — collaborators are real.

- Units: `DescribeSpec`, `withData` for table cases.
- API: `BehaviorSpec` + `@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers Postgres. Real stack over HTTP; the
  repository is injected only to assert what the DB holds. Assert observable behaviour, not internals.
- Kotest has no `@Testcontainers`: container is a `companion object` val started in `init` behind `dockerIsAvailable()`,
  with `extension(SpringExtension())` wiring Spring.
- `IsolationMode.InstancePerTest` + a per-instance random user id gives each test its own rows instead of cleanup.
- Concurrency is tested for real (`runConcurrently`, latch-released pool) — requests must overlap to exercise the index
  and the lock.
- Assert errors *don't* leak PII (`shouldNotContain`); capture logs with a logback `ListAppender`, since Spring's
  `OutputCaptureExtension` is a JUnit parameter resolver Kotest won't run.
- When a state is unreachable through the API, comment why instead of faking it (see the `NotEditable` note).

## Conventions

Comments explain *why*, especially non-obvious framework behaviour (`Long` not `long`, no `@Transactional`, processor
order). Keep that density; don't restate code. Cite spec ids where a rule comes from one
(`// I4 / FR1.5: one draft per user per product`). Javadoc on domain exceptions and service methods states the rule plus
`@throws`. Explicit imports, no wildcards. `ApplicationStatus` has only `DRAFT`; new states arrive with the FR that
introduces them.
