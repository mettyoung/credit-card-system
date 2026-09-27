# 11. Uploaded bytes never pass through the API

Status: Accepted

## Context

An upload endpoint that accepts the file holds a connection for the transfer, pulls up to 10 MB into heap
per request, and pays egress twice.

## Decision

The API issues a pre-signed URL bound to content type, length and sha256; the client PUTs straight to the
object store. On `complete` the API `HEAD`s the object and re-checks all three, then reads the first bytes
and compares them against the declared type's signature.

## Consequences

The store itself rejects a body that disagrees with the declaration, so a truncated or altered upload never
becomes an object.

The declared content type is still only a claim — the store never opens the file — so the magic-byte check
is the only thing that catches a PDF renamed as a JPEG. That is the case the spec is built around.

The read side must hold the same line: a download belongs behind a pre-signed GET, not proxied. An ArchUnit
rule keeps anything that answers an HTTP request from calling `Documents.contentFor` or naming its result.

An issued URL that is never used leaves a row to sweep, which is why the cleanup worker exists.
