# ADR-021: Generic Workflow Definition Model

## Status

Proposed

## Context

Key Handover and Inspection have been implemented as focused vertical slices with
dedicated runtime code. Product now needs a reusable, versioned, deterministic way
to *describe* workflows such as Key Handover without coupling the description to a
particular runtime implementation. This ADR proposes a generic workflow-definition
domain model that can be validated and serialized, but deliberately does not
introduce execution, persistence, or design-time tooling yet.

## Decision

Introduce a new bounded domain package, `com.sevenewf.workflow.domain.workflowdefinition`,
containing only definition-time concepts. The model is immutable, versioned with
semantic versions, and validated by a pure structural validator. JSON serialization
is implemented without external libraries so that the `domain` module retains its
existing architecture constraints (no dependencies on adapters, backend, contracts,
or third-party frameworks).

### Supported activity types

The initial model supports exactly these activity types:

- `START`
- `HUMAN_TASK`
- `SERVICE_TASK`
- `DECISION`
- `PARALLEL_SPLIT`
- `PARALLEL_JOIN`
- `CHILD_WORKFLOW`
- `WAIT_EVENT`
- `TIMER`
- `END`

No scripting, expression language, or executable code is attached to activities.
All policy, schema, timeout, and role references are identifiers only.

### Versioning rules

- Each workflow definition carries a semantic version (`major.minor.patch`, optional
  prerelease/build metadata).
- `DRAFT`, `VALIDATED`, `PUBLISHED`, and `RETIRED` statuses are supported.
- Published definitions are immutable: records, `List.copyOf`, and `Map.copyOf`
  prevent mutation; any material change requires a new semantic version.
- Definition equality and hash codes are deterministic because the model is composed
  of immutable value objects.

### Validation rules

The structural validator rejects definitions with any `ERROR` or `CRITICAL` finding.
Implemented rules include:

- missing workflow key;
- missing or invalid semantic version;
- missing START activity;
- more than one START activity;
- missing END activity;
- duplicate activity keys;
- transitions referencing unknown activities;
- unreachable activities;
- terminal activities with outgoing transitions;
- non-terminal activities without outgoing transitions;
- `PARALLEL_SPLIT` with fewer than two outgoing branches;
- `PARALLEL_JOIN` with fewer than two incoming branches or no `PARALLEL_SPLIT` ancestry;
- `DECISION` without named outcomes or with duplicate outcomes;
- `HUMAN_TASK` without eligible role or allowed outcomes;
- `CHILD_WORKFLOW` without correlation-key or business-key mapping;
- `WAIT_EVENT` without event type;
- `TIMER` without duration or duration policy reference;
- cycles without an explicitly declared loop policy (metadata key `loopPolicy`);
- conflicting transition priorities (same source, same outcome, same priority);
- unsupported activity-type combinations (e.g., `END` as source, `START` as target,
  direct `PARALLEL_SPLIT` to `PARALLEL_JOIN`, `CHILD_WORKFLOW` with multiple branches,
  outcome-bearing transitions from `START`, `PARALLEL_SPLIT`, or `PARALLEL_JOIN`).

Validation findings carry severity (`INFO`, `WARNING`, `ERROR`, `CRITICAL`), code,
message, workflow key, and optional activity or transition references. Validation
never mutates the definition.

### Serialization safety

- JSON uses stable field names and an explicit `"type"` discriminator for activities.
- A round-trip serializer and deserializer are included in the domain model.
- Unknown activity types are rejected during deserialization.
- No Java class names leak into JSON.
- No polymorphic deserialization based on class names is performed.
- No executable expressions are serialized.
- Missing required configuration produces parse or validation errors rather than
  silent defaults.

### Relationship to Key Handover

The generic model currently coexists with the existing Key Handover runtime. A
simplified Key Handover fixture validates successfully against the generic model,
but the production Key Handover code is intentionally not migrated in this slice.
The fixture proves that the generic vocabulary can express the Key Handover shape
(parallel clearances, child inspection workflow, final decision) without changing
runtime behavior.

### Relationship to future runtime

This slice defines only the *shape* of a workflow. A future runtime would consume
validated, published definitions to drive instances, but that runtime is out of
scope here. The definition model is intentionally free of instance state, timers,
message brokers, or persistence concerns.

## Consequences

- Product gains a shared, versioned vocabulary for workflow definitions.
- New workflow shapes can be described and validated before any runtime work begins.
- The domain module remains free of external frameworks, preserving existing
  architecture-test invariants.
- Manual JSON serialization requires ongoing maintenance if the model changes.

## Deliberately deferred capabilities

- workflow execution or instances;
- runtime state machines;
- timers running in real time;
- message or event broker integration;
- definition registry, persistence, or publication API;
- workflow designer UI or form builder;
- expression language or script tasks;
- assignment engine, SLA engine, escalation, or delegation;
- BRE/DMN evaluation;
- Oracle integration;
- AI workflow generation;
- migration of Key Handover or Inspection to the generic model.

## Acceptance criteria

This ADR is complete when implementation can demonstrate:

1. all required activity types and workflow fields are modeled;
2. structural validation rejects every listed invalid case;
3. valid definitions (linear, parallel, child, wait event, timer, simplified Key
   Handover) pass validation;
4. JSON round-trips deterministically and rejects unknown activity types;
5. published definitions are immutable and deterministic;
6. existing Key Handover, Inspection, and frontend behavior remain unchanged.
