# Onfido mock

WireMock speaking Onfido's real API. Mounted by both `compose.yaml` and the FR2 integration spec, so a stub
fixed for a test is fixed for local development and the two cannot drift.

The adapter targets the published API, so moving to real Onfido is `app.onfido.base-url` and a token — no code.

## Choosing a scenario

The scenario travels on the **applicant**, exactly as identity does in the real API: the adapter sends the last
name on `POST /applicants`, that call returns a per-scenario applicant id, and `POST /checks` is matched on it.
So a test picks its behaviour through ordinary request data, with no special wiring.

| Last name contains | `result` / `sub_result` | Maps to      | Exercises                                |
|--------------------|-------------------------|--------------|------------------------------------------|
| (anything else)    | `clear`                 | `VERIFIED`   | Happy path                               |
| `Fraud`            | `consider` / `suspected` | `FRAUD`     | A hit that still satisfies the requirement |
| `Unreadable`       | `consider` / `rejected` | `UNREADABLE` | `NEEDS_INFO`, re-upload, a second check — rejected every time |
| `Blurry`           | `rejected` on the first applicant, `clear` on the next (WireMock scenario `blurry`) | `UNREADABLE`, then `VERIFIED` | The `NEEDS_INFO` loop ending in approval (FR10's UI) |
| `Unavailable`      | `POST /checks` answers `503` every time | — | Retries run out → `FAILED` → requirement `UNAVAILABLE` → referred |
| `Caution`          | `consider` / `caution`  | `VERIFIED`   | An answer with a caveat, not a refusal   |
| `Flaky`            | `503` twice, then `201` | —            | Backoff, the lease, attempt counting     |
| `Timeout`          | 15 s delay on the first call | —       | The 10 s submit timeout                  |
| `Lostreply`        | `POST /checks` creates the check but answers after 12 s; `GET /checks?applicant_id=` lists it | `VERIFIED` | A retry adopting a check whose reply was lost, instead of paying twice |

`Flaky` uses a WireMock **Scenario** (`scenarioName` / `requiredScenarioState`) to answer differently across
calls. Reset it with `POST /__admin/scenarios/reset`.

## No webhook is fired here, on purpose

Every check is created `in_progress`, so the asynchronous path is always the one under test. Nothing in these
stubs calls back.

That is not a gap: **the reconciler makes the callback an optimisation, not a requirement.** It polls
`AWAITING_CALLBACK` checks and completes them, so with no webhook at all the flow still reaches
`CHECKS_COMPLETE` — which is exactly the `LOSTHOOK` case the design worries about, and it is now the default
path in development. Set `app.onfido.poll-after` short and watch it work.

A webhook needs a URL that reaches the application, which differs between Docker Desktop, Linux CI and a
developer's machine, and WireMock's templating has no HMAC helper to sign the body with. Rather than bake a
fragile `host.docker.internal` into committed stubs, the integration spec builds a correctly signed callback and
delivers it itself. Signature verification is therefore still exercised for real, on the real code path — see
`IdentityVerificationTest`, which covers a genuine callback, a forged signature and a duplicate delivery.

To exercise the fast path by hand, add a `postServeActions` webhook to a `checks-create-*.json` and sign the
body with `app.onfido.webhook-token`:

```bash
printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$ONFIDO_WEBHOOK_TOKEN" -hex
```
