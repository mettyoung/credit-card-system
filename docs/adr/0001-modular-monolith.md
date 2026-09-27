# 1. A modular monolith, not services

Status: Accepted

## Context

The system takes an application and gathers evidence from four third parties. Each check is independently
slow, independently unreliable and independently retried, which is the shape people usually answer with a
service per check.

Nothing in the scope needs independent deployment or independent scaling, and there is one team. Splitting
now would buy a network boundary and pay for it with distributed transactions, a broker, and a correlation
story for every failure.

## Decision

One deployable, with module boundaries enforced in the build rather than by the network. Spring Modulith
verifies them: each module declares its allowed dependencies in `package-info.java`, and reaching into
another module's internals fails `ModuleStructureTest`.

## Consequences

The workflow and its evidence commit together, so there is no saga to write and no eventual consistency to
explain.

The boundaries have to be enforced by something, because the compiler alone will not: that is what the
verification test is for. It found real coupling the first time it ran — three dependency cycles and a
dozen reaches into other modules' repositories.

Extracting a module later is a refactor rather than a rewrite, because the seams are already named and
already checked. That is the option this keeps open, not a promise to take it.
