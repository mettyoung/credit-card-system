# 8. The architecture diagrams are hand-maintained Mermaid in one page

Status: Accepted

## Context

Generated diagrams stay true and say little: they show what the code imports, not why. Drawn diagrams say
more and go stale. Spring Modulith can generate module canvases, and does, but they answer a narrower
question than the C4 levels do.

## Decision

`docs/c4-model.html` holds all four C4 levels plus deployment and two dynamic views, as Mermaid source in a
single self-contained page rendered from a CDN. Each element carries its state: built, or designed for a
named FR.

## Consequences

There is no build step and no image to regenerate; a diagram is editable text in a diff.

It goes stale unless maintained, so the rule is that behaviour which moves a box or an arrow changes the
diagram in the same commit.

Mermaid treats several characters as syntax — `@` and `:` among them — so node labels are quoted. An
unquoted one fails the whole block rather than one line.
