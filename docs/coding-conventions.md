# Coding conventions

What this codebase does and why, as it actually stands. The [ADRs](adr/) record the decisions; this records
the habits that follow from them, so a new file looks like the ones beside it.

Rules here are enforced by something wherever possible — the compiler, a test, or the build — because a
convention nothing checks is a preference.

## Modules

**A module is a feature, not a layer.** `application`, `audit`, `document`, `vendor`, plus a root `shared`.
A new feature arrives as a sibling, never as a new top-level layer.

**Each module exposes a facade and hides the rest.** The root package holds an interface (`Applications`,
`Audits`, `Documents` + `UploadPort`, `IdentityChecks`) plus the commands, responses and enums those
signatures name. Everything else is package-private in a single `internal` package.

The facade answers with a response type, never the aggregate. If the aggregate could leave, hiding it would
be theatre.

**`internal` is one package on purpose.** Package-private does not span sub-packages, so `web`/`service`/
`domain`/`persistence` would force every type back to `public`. The layers survive as a rule about what a
class may do, not as a package name:

| Role | Owns | Must not |
|---|---|---|
| web | header and DTO parsing, status mapping, problem JSON | business rules; touching a repository |
| service | load-by-owner, preconditions, transaction boundaries, exception translation | field validation |
| domain | state guards; value objects validate their own fields | know about HTTP or JPA exceptions |
| process | deciding the next step and applying it in one transaction | calling a vendor, or holding a transaction over one |
| worker | claiming work, calling out, recording the result | transitioning an application |

**When two modules must talk both ways, declare the port in the module being called and implement it in the
caller.** `ApplicantSubjects` lives in `vendor` and is implemented in `application`; `DomainEventListener`
lives in `shared/outbox` and is implemented by the orchestrator. Widening `allowedDependencies` to make a
cycle compile is the wrong fix.

Enforced by `ModuleStructureTest` (Spring Modulith) and, since the `internal` split, by the compiler.

## Naming

- Input types are **commands**: `CreateDraftCommand`, `UpdateDraftCommand`, `RequestUploadCommand`. They are
  the HTTP request body *and* the module's input — one type, no field-by-field copy.
- Aggregate methods are named for the command they apply (`Application.on(UpdateDraftCommand)`) or for the
  transition (`submit`, `markUploaded`, `markExpired`), never for the fields they set.
- Ports end in `Port` (`ObjectStorePort`, `IdvPort`); adapters name the technology (`S3ObjectStoreAdapter`,
  `OnfidoIdvAdapter`). The technology appears in the adapter's name and nowhere else.

## Types

**The aggregate is the JPA entity.** `@Getter` only — never `@Setter` or `@Data`. `protected` no-arg
constructor for JPA, private real one, static factory. Ids are pre-assigned `UuidV7.generate()`, so
`ORDER BY id DESC` replaces a `created_at` column. `@Version` is `Long`, not `long`.

**Value objects validate in their constructor**, so every instance is valid, and persist through an
`AttributeConverter` that routes loads back through the same constructor. Converters are package-private:
they are named only by the `@Convert` beside them.

**Records for anything that is only data** — commands, responses, ports' nested results. A parameter object
whenever a signature would otherwise take more than three arguments, and only when the arguments are one
idea. `applicationId` and `userId` stay separate where the signature is already at three; they became
`Document.Owner` only where grouping them took a factory from seven arguments to three.

**Bean Validation on DTOs is shape only** (`@NotNull`). The rules live in the value objects, where no caller
can bypass them — which is why a command's fields are the value objects themselves and binding the body *is*
the validation.

## Errors

Domain errors extend `DomainException` and name a `Category` — `INVALID_VALUE` → 422, `CONFLICTING_STATE` →
409, `NOT_FOUND` → 404. The handler switches exhaustively, so a new category breaks the build and a new
exception of an existing one needs no web change.

- One condition, one exception. `UploadIncompleteException` means no object arrived;
  `DocumentAlreadySettledException` means one arrived and was judged. Sharing them answered "no uploaded
  object was found" alongside `currentStatus: UPLOADED`.
- No request value or PII in a message or a detail — describe the rule. Asserted with `shouldNotContain`.
- Not-found and not-yours are one outcome (`findByIdAndUserId`), so ids cannot be probed. A malformed path
  UUID also answers 404.
- A business verdict is not an error: a rejected document is `200 {status: INVALID, reason}`.

## Persistence and concurrency

**Flyway owns the schema**; `ddl-auto=validate` only checks the mapping. Partial unique indexes and check
constraints carry invariants the code cannot.

**The database settles concurrency, never check-then-act.** Insert and translate the violation by constraint
name. Transaction boundaries are explicit where the obvious annotation would be wrong, and each one says why
in a comment — `createDraft` is not `@Transactional` because the aborted transaction cannot serve the
follow-up lookup; `updateDraft` uses its own `TransactionTemplate` so a lost race is translated after
rollback. Always `saveAndFlush`.

**Every timer is a column plus a poller.** Nothing is held in memory, so a restart loses nothing.

**Workers claim, call, then record — three transactions, never one.** A third-party call never happens
inside a transaction.

## Comments

Comments explain *why*, especially non-obvious framework behaviour: `Long` not `long`, the missing
`@Transactional`, the Lombok-before-MapStruct processor order, why `max` sizes the magic-byte read. They do
not restate the code.

Cite spec ids where a rule comes from one (`// I4 / FR1.5: one draft per user per product`). Javadoc on
domain exceptions and service methods states the rule plus `@throws`.

When a state is unreachable through the API, say why instead of faking a test for it.

Explicit imports, no wildcards, and no fully-qualified names in a body or a javadoc. Where a name collides
with a dependency's, rename ours — `ObjectStoreConfiguration`, not a qualified `S3Configuration`.

## Tests

Kotlin and Kotest on the JUnit platform. **No mocking library anywhere** — collaborators are real.

- Units: `DescribeSpec`, `withData` for table cases.
- API: `BehaviorSpec` + `@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers. The real stack over
  HTTP; the database is read only to assert what it holds.
- Kotest has no `@Testcontainers`: the container is a `companion object` val started in `init` behind
  `DockerAvailable`, with `extension(SpringExtension())` wiring Spring.
- `IsolationMode.InstancePerTest` plus a per-instance random user id gives each test its own rows instead of
  cleanup.
- Concurrency is tested for real (`runConcurrently`, latch-released pool). Requests must overlap or the
  index and the lock are not exercised at all.
- Specs set `app.workers.enabled=false` and drive each worker by hand, so assertions are about behaviour
  rather than timing.
- A spec that reads another module's rows reads them as rows, through `JdbcTemplate`.
- Bytes reach the object store through a real pre-signed PUT, never a test-only write path.

**A test that cannot fail is not a test.** Every non-obvious rule here was verified by breaking the code and
watching the right case go red.

## Build

Java 21 main sources, Kotlin tests. MapStruct with `-Amapstruct.unmappedTargetPolicy=ERROR`, so a new
response field with no source fails compilation; Lombok's processor stays ordered before MapStruct's with
`lombok-mapstruct-binding` between.

Integration specs self-skip without Docker, so a green build without Docker means less than it looks.
