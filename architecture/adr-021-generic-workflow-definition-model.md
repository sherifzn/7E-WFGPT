# ADR-021: Generic Workflow Definition Model

## Status

Accepted

## Context

Key Handover and Inspection have been implemented as focused vertical slices with
dedicated runtime code. Product needs a reusable, versioned, deterministic way to
*describe* workflows such as Key Handover without coupling the description to a
particular runtime implementation. This ADR accepts a generic workflow-definition
domain model that can be validated and serialized, but deliberately does not
introduce execution, persistence, or design-time tooling.

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

### Explicit typed loop policies

Cycles are not allowed by default. Every simple cycle in the transition graph must
be covered by at least one explicit `LoopPolicy` attached to a transition within
that cycle.

`LoopPolicy` is a typed value object with:

- `loopPolicyKey` — stable identifier;
- `loopType` — `BOUNDED`, `CONDITION_CONTROLLED`, or `POLICY_CONTROLLED`;
- `maxIterations` — required for `BOUNDED`, must be positive;
- `exitConditionRef` — required for `CONDITION_CONTROLLED`;
- `policyRef` — required for `POLICY_CONTROLLED`;
- `timeoutPolicyRef` — optional for all types.

A loop policy on one transition does not authorize a different cycle.

### Explicit parallel gateway pairing

`PARALLEL_SPLIT` and `PARALLEL_JOIN` activities share a nonblank `pairKey`. Within a
definition, each pair key identifies exactly one split and exactly one join. Nested
parallel pairs are allowed. Validation rejects:

- missing pair keys;
- unmatched splits or joins;
- duplicate pair keys on splits or joins;
- crossing or mismatched pairs;
- branches from a split that cannot reach its paired join;
- joins that combine branches from unrelated splits.

Runtime token semantics are out of scope.

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
- cycles without an explicit loop policy on a transition in the cycle;
- `PARALLEL_SPLIT` with fewer than two outgoing branches;
- `PARALLEL_JOIN` with fewer than two incoming branches;
- missing, unmatched, duplicate, or crossing parallel gateway pair keys;
- `PARALLEL_JOIN` combining branches from unrelated splits;
- branches from a split that cannot reach the paired join;
- `DECISION` without named outcomes or with duplicate outcomes;
- `HUMAN_TASK` without eligible role or allowed outcomes;
- `CHILD_WORKFLOW` without correlation-key or business-key mapping;
- `WAIT_EVENT` without event type;
- `TIMER` without duration or duration policy reference;
- conflicting transition priorities (same source, same outcome, same priority);
- unsupported activity-type combinations (e.g., `END` as source, `START` as target,
  direct `PARALLEL_SPLIT` to `PARALLEL_JOIN`, `CHILD_WORKFLOW` with multiple branches,
  outcome-bearing transitions from `START`, `PARALLEL_SPLIT`, or `PARALLEL_JOIN`).

Validation findings carry severity (`INFO`, `WARNING`, `ERROR`, `CRITICAL`), code,
message, workflow key, and optional activity or transition references. Validation
never mutates the definition.

### Serialization boundary

- JSON uses stable field names and an explicit `"type"` discriminator for activities.
- A round-trip serializer and deserializer are included in the domain model.
- Unknown activity types are rejected during deserialization.
- No Java class names leak into JSON.
- No polymorphic deserialization based on class names is performed.
- No executable expressions are serialized.
- Missing required configuration produces parse or validation errors rather than
  silent defaults.
- The workflow-definition domain model remains framework-independent.
- The current deterministic JSON codec is a temporary local canonical codec.
- Future registry or runtime infrastructure may move serialization behind a
  contracts or adapter boundary.
- External JSON libraries must not leak polymorphic class names into the contract.
- The canonical JSON schema and type discriminators remain stable regardless of
  where the implementation is located.

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
- Loop and parallel-gateway contracts are explicit and type-safe, removing the need
  for metadata conventions.
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

This ADR is accepted when implementation can demonstrate:

1. all required activity types and workflow fields are modeled;
2. structural validation rejects every listed invalid case;
3. valid definitions (linear, parallel, child, wait event, timer, simplified Key
   Handover) pass validation;
4. explicit loop policies authorize cycles only when correctly typed and placed;
5. explicit gateway pair keys validate single/nested pairs and reject mismatches;
6. JSON round-trips deterministically and rejects unknown activity types;
7. published definitions are immutable and deterministic;
8. existing Key Handover, Inspection, and frontend behavior remain unchanged.
