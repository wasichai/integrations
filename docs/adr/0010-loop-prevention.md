# ADR-0010: Echoes are detected by content hash and causation, not by who made the change

**Status**: accepted · 2026-11-04

## Context

Writing to B produces a change event on B, which would flow back to A. Filtering by the integration user's name fails
when the same account is used by people, and some providers do not report the author at all.

## Decision

- After writing to a side, the link stores the hash of the mapped values written there. An incoming change whose mapped
  hash equals that hash is our echo: versions are updated, nothing is written back.
- Events the engine causes carry the run's correlation id and the causing event as causation id; the run id is on every
  write job. A chain deeper than a configured limit is stopped and reported.
- Only mapped fields are hashed, normalized by the transforms, so unrelated provider-side changes (formulas, system
  fields) are not echoes and do not loop either: they produce no mapped diff.

## Consequences

- No provider-specific "known origin" marker is required, though a connector may use one as an extra signal.
