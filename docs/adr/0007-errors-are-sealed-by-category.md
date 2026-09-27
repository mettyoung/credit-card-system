# 7. Errors are sealed by category, not by exception class

Status: Accepted

## Context

Mapping each exception to a status in the web layer means every new exception needs a web change, and
nothing makes you do it — a missed one becomes a 500.

## Decision

Domain errors extend `DomainException` and name a `Category`: `INVALID_VALUE` → 422, `CONFLICTING_STATE` →
409, `NOT_FOUND` → 404. `ApiExceptionHandler` switches exhaustively over the category.

The problem `type` is derived from `code()`, and extra members from `details()`, whose keys are the rejected
fields.

## Consequences

A new category breaks the build until it is mapped; a new exception of an existing category needs no web
change at all. That is the trade the switch is there to make.

The category is the contract, which is what lets the exception classes stay internal to their module.

Two rules ride along and are asserted: no message or detail ever echoes a request value, and not-found and
not-yours are one outcome (`findByIdAndUserId`), so ids cannot be probed.
