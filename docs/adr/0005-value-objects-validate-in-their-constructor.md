# 5. Value objects validate in their own constructor

Status: Accepted

## Context

Field rules can live in Bean Validation annotations on the request DTO. That works for callers who arrive
through that DTO and for nobody else, and it leaves the model able to hold values nothing checked.

## Decision

Each declared field is a value object that validates in its constructor, so every instance is valid. DTO
fields carry no rules; where a DTO is the module's input, its components are the value objects themselves,
so binding the body *is* the validation.

Loads route back through the same constructor via an `AttributeConverter`, so a row that breaks the rules
fails on read.

A multi-field save builds every value object before assigning any, so a rejection cannot half-apply.

## Consequences

There is no way to reach a valid-looking aggregate holding an unchecked value, whatever the caller.

Validation failures surface while the body is being bound, wrapped by Jackson — so the exception handler
walks the cause chain to keep the same 422 a service-level refusal would produce.

Converters need their own spec: the integration tests only ever round-trip rows this application wrote, and
those are valid by construction.
