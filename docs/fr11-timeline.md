# FR11 — Live Timeline

Parent: [Credit Card Application](../README.md). Previous: [FR10 — Web UI](fr10-web-ui.md).
Spring MVC `SseEmitter`; the FR10 web UI.

After submit, the web UI shows a status sentence and nothing else. Everything interesting — a vendor answering `503`
and the worker backing off, a reply lost and the check adopted instead of paid for twice, a webhook that never came and
the reconciler fetching the result anyway — happens out of sight. This increment streams it: a **live timeline of what
the system did**, pushed to the browser with Server-Sent Events as it happens.

It is a **development view**, not part of the applicant's experience. It shows vendor outcomes and referral reasons,
which FR8 deliberately keeps from the applicant, so it is off unless a property turns it on.

## 1. Requirements

| #      | Functional                                                                                                       |
|--------|------------------------------------------------------------------------------------------------------------------|
| FR11.1 | After submit, the status screen shows a live timeline of what happened to the application, newest last.         |
| FR11.2 | Each failure mode is visibly distinct: a retry names its failure and the wait; an adopted check says so; a reconciled result says it arrived without a webhook; a failure says the attempts ran out. |
| FR11.3 | A reconnecting browser resumes where it left off, with nothing missed or repeated.                              |
| FR11.4 | The stream ends once the application is terminal and its last events are sent.                                 |
| FR11.5 | The timeline is available only when `app.ui.timeline.enabled` is true, and only for the application's owner.   |

Out of scope: a timeline for reviewers, push for anything other than this view, and events the system does not record
— a duplicate webhook is a no-op by design (FR5), so it leaves nothing to show.

| Quality          | Requirement                                                                                          |
|------------------|------------------------------------------------------------------------------------------------------|
| Restart-safe     | The stream is read from the database, so a restart, or a second instance, loses nothing.             |
| No tipping off   | Off by default. Production does not set the property; development does.                             |
| No PII           | It streams audit payloads, which already hold ids and codes only (FR2).                               |
| Cheap            | One query per open stream per second, by `(application_id, seq)`, which is already unique and indexed. |

## 2. What is streamed: the audit log

The audit log already records nearly every step, in order, per application (`seq` is contiguous, FR2). The timeline is
that log, read back. Two steps are missing, and they are exactly the interesting failure modes, so this increment
records them:

| New event type          | Written when                                                            | Payload                                              |
|-------------------------|-------------------------------------------------------------------------|------------------------------------------------------|
| `VENDOR_CHECK_RETRY`    | A vendor call failed in a way worth retrying, and attempts remain        | `vendorCheckId`, `attempt`, `failureCode`, `nextAttemptAt` |
| `VENDOR_CHECK_ADOPTED`  | A retry found the check an earlier, lost attempt had already created     | `vendorCheckId`, `attempt`                            |

`VENDOR_CHECK_RETRY` is written by `VendorWorker` where it schedules the retry. `VENDOR_CHECK_ADOPTED` needs the
adapter to say it adopted rather than created: `VendorResult.Pending` and `Completed` gain an `adopted` flag, set by
`OnfidoIdvAdapter` when `existingCheck` found one. Both are useful audit facts in their own right, not just for the view.

### 2.1 Reading the log

FR2 left the audit log write-only ("nobody can read the log"). The timeline is its first reader, so `Audits` gains one
method, in the audit module's API:

```java
/** Events recorded for an application after {@code afterSeq}, in order. */
List<RecordedEvent> since(UUID applicationId, long afterSeq);

record RecordedEvent(long seq, AuditEventType type, Actor actor, Map<String, Object> payload, Instant at) { }
```

No actor id: the timeline needs to know a reviewer decided, not which one.

## 3. API

| Method | Path                                    | Purpose                                                       |
|--------|-----------------------------------------|---------------------------------------------------------------|
| GET    | `/v1/applications/{id}/timeline`        | `text/event-stream` of the application's audit events (FR11)  |

- `X-User-Id`, loaded by owner like every other applicant endpoint; not yours or absent is `404`.
- `Last-Event-ID` resumes after that `seq`; without it the stream starts from the beginning.
- `404` when `app.ui.timeline.enabled` is false, so a production build does not reveal the endpoint exists.

Each event:

```
id: 14
event: VENDOR_CHECK_RETRY
data: {"seq":14,"type":"VENDOR_CHECK_RETRY","actor":"SYSTEM","at":"2026-10-09T10:00:04Z",
       "payload":{"vendorCheckId":"…","attempt":2,"failureCode":"unavailable-503","nextAttemptAt":"…"}}
```

## 4. High-Level Design

### 4.1 Server: poll the log, push to the browser

The events are written by workers and the orchestrator in other transactions, possibly on another instance, so there
is no in-memory event to forward. The stream reads the database instead — the project's usual answer, a column and a
poller (`seq` is the column):

```
on connect:  check ownership; lastSeq = Last-Event-ID or 0
every 1 s:   events = audits.since(applicationId, lastSeq)
             send each (id = seq); lastSeq = last seq sent
             if the application is terminal and nothing new arrived: complete the stream
```

One scheduled task serves every open stream, so an idle browser costs a query a second, not a thread. A stream also
completes after 10 minutes; the browser reconnects with `Last-Event-ID` and loses nothing. Postgres `LISTEN/NOTIFY`
would push without polling, but it ties the view to one database feature and a held connection per listener — not worth
it for a development view.

### 4.2 Browser: `fetch`, not `EventSource`

The built-in `EventSource` cannot send headers, so it cannot send `X-User-Id`. `api.js` reads the stream with `fetch`
and a small line parser instead, keeping the same placeholder header — and the same ownership check — as every other
call. It reconnects on its own with `Last-Event-ID` when the stream ends before the application is terminal.

### 4.3 The view

A "Behind the scenes" panel under the status, marked *development only*, rendering each event as a plain line with the
time since submit:

| Event                              | Shown as                                                                  |
|------------------------------------|---------------------------------------------------------------------------|
| `WORKFLOW_STARTED`                 | Submitted                                                                 |
| `VENDOR_CHECK_QUEUED`              | Identity check queued · (again, with the vendor's reference) Onfido accepted it |
| `VENDOR_CHECK_RETRY`               | Attempt 2 failed — vendor unavailable (503). Retrying in 4 s              |
| `VENDOR_CHECK_ADOPTED`             | Reply was lost — found the check Onfido had already created; not charged twice |
| `WEBHOOK_RECEIVED`                 | Onfido's webhook arrived                                                  |
| `VENDOR_CHECK_COMPLETED`           | Onfido answered: VERIFIED · (with `recoveredBy`) …fetched by the reconciler, no webhook |
| `VENDOR_CHECK_FAILED`              | Gave up after 5 attempts — vendor unavailable                             |
| `REQUIREMENT_CHANGED`              | Identity: received / needs evidence / unavailable                          |
| `STATUS_CHANGED`                   | Application: verifying / needs info / checks complete / expired           |
| `DECISION_MADE`                    | Decision: approved / referred (suspected fraud)                            |

Retries, failures and recoveries get a warning colour; the outcome gets the status colour. The panel appears only when
the endpoint answers — a `404` (feature off) hides it.

## 5. Configuration

| Property                    | Default (code) | `application.properties` (development) |
|-----------------------------|----------------|-----------------------------------------|
| `app.ui.timeline.enabled`   | `false`        | `true`                                  |

## 6. Migrations

None: audit event types are stored as `text`.

## 7. Tests

| Level | Spec                        | Covers                                                                                     |
|-------|-----------------------------|--------------------------------------------------------------------------------------------|
| API   | `AuditLogTest`              | `since` returns events after a `seq`, in order, and only that application's               |
| API   | `IdentityVerificationTest`  | The vendor-down scenario records a `VENDOR_CHECK_RETRY` per failed attempt before the failure; the lost-reply scenario records `VENDOR_CHECK_ADOPTED` |
| API   | `TimelineStreamTest`        | Over real HTTP: the stream sends the application's events in order with `seq` ids; resumes after `Last-Event-ID`; completes once terminal; `404` for another user's application and when the property is off |

`TimelineStreamTest` uses a real port and reads the stream line by line: MockMvc's async dispatch does not model a
long-lived stream well, and the point is what a browser receives.

## 8. Open questions

- **Polling the log once a second.** Fine for a development view with a handful of open streams. A production push
  feature would want `LISTEN/NOTIFY` or a broker.
- **A reader for the audit log now exists.** It is narrow — one application, after a `seq` — and the property keeps it
  out of production, but FR2's question of who may read an audit trail becomes real the day this view is turned on for
  anyone but a developer.
