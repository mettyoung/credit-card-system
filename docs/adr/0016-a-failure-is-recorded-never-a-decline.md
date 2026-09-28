# 16. An unanswerable check is recorded, never a decline

Status: Accepted

## Context

A vendor can time out, fail repeatedly, or never call back. Treating that as a negative answer would decline
an applicant for our own infrastructure's behaviour.

## Decision

A check that cannot be answered settles its requirement as `UNAVAILABLE`, which still satisfies the
completion guard. The workflow reaches `CHECKS_COMPLETE` with the gap recorded and visible.

Bad news that *is* an answer — a FRAUD verdict — settles the requirement normally. Judging it is the
ruleset's job, and there is no ruleset yet.

## Consequences

A dead vendor cannot hang an application, and cannot decline one either.

`CHECKS_COMPLETE` means "every requirement settled", not "every requirement passed", so whatever consumes
`ChecksCompleted` must read the requirements rather than assume them. Nothing consumes it yet; this is the
constraint on whoever does.

Every raw vendor response is stored as it arrived, so a later decision can be made on evidence that was not
normalised away.
