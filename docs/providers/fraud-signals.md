# Provider — Fraud signals (none: internal)

Parent: [Credit Card Application](../../README.md) §0 row 5.

| | |
|---|---|
| **Question** | Is the application genuine? |
| **Requirement** | None — no `EvidenceRequirement` type |
| **VendorCheck type** | None |
| **Port** | None |
| **Mode** | **Internal** — no network call, no provider, nothing to wait for |
| **Paid** | No |
| **Status** | **Deferred.** Nothing below is built or scheduled in this scope. |

This row exists to be explicit that question 5 has **no provider**, and to record why. It is the odd one out in every
respect, and the reasons are worth writing down rather than leaving as a gap in the table.

## 1. Why there is no provider

The other four questions are answered by data we do not have: a document's authenticity, a watchlist, a credit file, a tax
record. Question 5 is answered by data **only we** have — what this applicant has done on our own platform, and what other
applicants have done that looks like it.

| | Questions 1–4 | Question 5 |
|---|---|---|
| Evidence lives | At a third party | In our own tables |
| Interaction | HTTP, paid, can time out | A SQL query in the same transaction |
| Can be `UNAVAILABLE` | Yes — a vendor can be down | No — our own database is the workflow's database |
| Needs idempotency keys | Yes | No |
| Latency budget | Seconds, sometimes minutes | Microseconds |

So it is not a fifth adapter with an unusual transport. It is a different kind of check: a **precondition** rather than an
evidence-gathering step, and in the deferred design it runs before any paid call precisely so a fraudulent application
never costs us four vendor fees.

Third-party fraud vendors (device intelligence, consortium data) are named in the parent spec's out-of-scope list. If one
is ever adopted, it becomes a conventional provider with its own spec alongside the other four — but the signals below
stay internal regardless.

## 2. The signals, when this is built

Two families, both computed from our own history.

**Velocity** — how often this identity has applied recently. Four dimensions: `NATIONAL_ID`, `PHONE`, `EMAIL`, `DEVICE`.

For each, `valueHash = HMAC(pepper, normalizedValue)`, then

```sql
INSERT INTO velocity_counter (dimension, value_hash, day, count)
VALUES (:dimension, :valueHash, current_date, 1)
ON CONFLICT (dimension, value_hash, day) DO UPDATE SET count = velocity_counter.count + 1
RETURNING count
```

summed over a 24 h and a 30 d window. The upsert makes the count exact under concurrency — no read-then-write, the same
principle as the partial unique index on drafts.

**Duplicates** — another application with the same national id, phone or email hash under a **different** `userId`, either
non-terminal or decided in the last 90 days. The same user and product is already covered by FR1.5's unique index, so this
looks only for the cross-user case, which is the one that indicates impersonation rather than a double-tap.

## 3. Two constraints that survive the deferral

These are worth fixing now because getting them wrong later is expensive:

- **Hash with a pepper, never store the value.** The counter table would otherwise become a searchable index of every
  phone number and national id that ever applied — a worse liability than the application table itself, because it has no
  owner and no retention story. Hashing keeps exact matching and loses the ability to read it.
- **`X-Device-Id` is client-supplied and spoofable.** It adds signal, not proof. A design that treats a device id as an
  identity will be defeated by anyone who clears it, so it may contribute to a referral and must never be the sole reason
  for one.

## 4. What this scope does instead

Nothing. There is no velocity counter table, no duplicate lookup and no `X-Device-Id` header in the FR5 API. An
application that is entirely fraudulent still has its four checks run and paid for, and still reaches
`CHECKS_COMPLETE`.

That is a real cost of the scope cut, stated plainly: **screening-before-spend is the main reason fraud signals run first
in the full design**, and deferring them means this increment pays vendor fees it could have avoided. Acceptable while the
system decides nothing and the traffic is test traffic; not acceptable before real applicants and real invoices.

## 5. To confirm before building

- Velocity thresholds per dimension, which are a risk decision informed by real traffic — guessing them now would produce
  numbers nobody trusts.
- The pepper's storage and rotation. Rotating it invalidates every existing counter, so the rotation story has to exist
  before the first row is written.
- Retention for `velocity_counter`. Rows are keyed by day, so expiry is a delete by date — but how many days is a
  compliance answer.
- Whether a declined-for-fraud application may be told why. It generally may not: telling an applicant they tripped a
  fraud rule teaches them the rule. The parent spec's deferred notification design maps such codes to a generic reason.
