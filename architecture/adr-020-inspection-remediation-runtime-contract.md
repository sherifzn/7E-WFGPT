# ADR-020: Inspection Request and Remediation Runtime Contract

## Status

Accepted

## Context

Key Handover currently uses an opaque `InspectionConnector` that only exposes valid/not-valid
status and a child-workflow ID. It cannot durably model attempts, remediation tasks, cancellation,
history, duplicate prevention, or restart recovery. Task 004 needs these capabilities without
introducing a generic workflow engine or Work Management Layer.

## Decision

Inspection Request is a separate durable **Inspection Process aggregate**, not data embedded in
`KeyHandoverState`. It is identified by `InspectionProcessId` and has the stable business key:

```text
propertyReference + inspectionType + keyHandoverRequestReference
```

The parent reference is the typed Key Handover request ID. The business key is unique in the local
synthetic tenant. The aggregate owns attempts, remediation cycles, fixed task lifecycle, process
state, audit history, and pending parent-resume events. Key Handover continues to own its own
prerequisite barrier and clearance branches.

### Alternatives considered

- **Embed inspection in Key Handover:** rejected because it couples unrelated attempt/remediation
  history to clearance-state serialization and prevents focused reuse.
- **Extend `InspectionConnector` with durable state:** rejected because a connector is an external
  boundary, not an aggregate, task, or audit owner.
- **Build a generic workflow/task engine:** rejected as unapproved and unnecessary for two fixed
  task types.

## Runtime contracts

### Correlation and duplicates

`InspectionProcessService.requestOrCorrelate(InspectionRequestCommand)` performs one atomic
business-key lookup.

- A valid unexpired passed inspection for the business key is returned with
  `InspectionCorrelated`; no process or task is created.
- A non-terminal process is returned with `InspectionCorrelated`; no duplicate process or active
  task is created.
- Otherwise the service creates `REQUESTED` and exactly one inspection task.
- Equivalent repeated commands, identified by business key, command type, and causation ID, return
  the prior result and emit `DuplicateCommandIgnored`.
- A materially conflicting duplicate, stale version, or invalid state is a conflict (`409`).

### Parent resume

A passed valid attempt appends `InspectionPrerequisiteSatisfied` in the same transaction as the
attempt result, task completion, and audit events. A local
`InspectionPrerequisiteSatisfiedHandler` loads the parent and issues the existing typed Key
Handover inspection-resume command with the passed evidence reference.

The handler uses inspection process ID plus passed attempt ID as its idempotency identity. It emits
`ParentWorkflowResumeRequested` then `ParentWorkflowResumed`. Key Handover resume remains
idempotent and resumes only the waiting Handover branch; Finance and Legal are unchanged. Pending
events persist and are retried after restart. This ADR selects no external broker.

### Inspection validity

`LocalInspectionValidityPolicy` is versioned `inspection-validity-v1-local`:

- `PASSED` is valid for 30 calendar days after completion.
- Expired `PASSED`, `FAILED`, and `CANCELLED` attempts cannot satisfy the prerequisite.
- Every reinspection is a new immutable attempt.

## State models

### Inspection process

| State | Allowed transitions |
| --- | --- |
| `REQUESTED` | `ASSIGNED`, `CANCELLED` |
| `ASSIGNED` | `IN_PROGRESS`, `CANCELLED` |
| `IN_PROGRESS` | `PASSED`, `FAILED`, `CANCELLED` |
| `PASSED` | `COMPLETED` |
| `FAILED` | `WAITING_FOR_REMEDIATION`, `CANCELLED` |
| `WAITING_FOR_REMEDIATION` | `WAITING_FOR_REINSPECTION`, `CANCELLED` |
| `WAITING_FOR_REINSPECTION` | `ASSIGNED`, `CANCELLED` |
| `CANCELLED`, `COMPLETED` | none |

`PASSED` emits the parent-resume event before terminal `COMPLETED`. Cancellation requires a reason,
is terminal, preserves history, leaves the parent blocked, and rejects later progress commands.

### Remediation cycle

Each failed attempt creates at most one remediation cycle:

| State | Allowed transitions |
| --- | --- |
| `REQUIRED` | `ASSIGNED`, `CANCELLED` |
| `ASSIGNED` | `IN_PROGRESS`, `CANCELLED` |
| `IN_PROGRESS` | `COMPLETED`, `REJECTED`, `CANCELLED` |
| `COMPLETED`, `REJECTED`, `CANCELLED` | none |

`COMPLETED` requires a resolution summary and synthetic remediation reference, then creates exactly
one reinspection attempt and one inspection task. A later failed reinspection can create another
cycle only after the previous cycle is completed.

## Persistence contracts

`InspectionProcessRepository` provides `findById`, `findByBusinessKey`, `findValidPassedByBusinessKey`,
`insertIfAbsent`, and version-checked `commit`. The business key has a local uniqueness constraint.

`InspectionTaskRepository` persists process-owned inspection/remediation tasks. Each task stores:

- task ID, process ID, task type, status, required role, and assignee;
- created, claimed, and completed timestamps;
- outcome and version; and
- correlation and causation IDs.

One command atomically writes aggregate state/version, changed task, immutable audits, and pending
parent-resume events. Optimistic concurrency uses `DomainVersion`. The existing local atomic-file
snapshot approach may be reused, with a separate inspection snapshot containing process/task
records, business-key index, pending audits, and pending events. Restart reconstructs all records
and drains pending audits/events. Existing Key Handover snapshots require no migration.

## Event contracts

Mandatory events are:

- `InspectionRequested`, `InspectionCorrelated`, `InspectionTaskCreated`,
  `InspectionTaskClaimed`, `InspectionCompleted`, `InspectionPassed`, `InspectionFailed`, and
  `InspectionCancelled`;
- `RemediationRequired`, `RemediationTaskCreated`, `RemediationTaskClaimed`,
  `RemediationCompleted`, `RemediationRejected`, and `RemediationCancelled`;
- `ReinspectionRequested`, `ReinspectionCompleted`, `ParentWorkflowResumeRequested`,
  `ParentWorkflowResumed`, `DuplicateCommandIgnored`, and `UnauthorizedActionRejected`.

Every event contains event ID/type, process ID/business key, applicable attempt/task ID, parent Key
Handover reference, actor, timestamp, prior/new state, correlation ID, causation ID, aggregate
version, and validity-policy version when validity is evaluated. Events are immutable and contain
only synthetic evidence/remediation references, never payloads.

## Authorization

Authorization is enforced in the inspection application service, never only in the frontend.

| Role | Actions |
| --- | --- |
| Inspection Officer | Claim and complete assigned inspection/reinspection tasks. |
| Remediation Officer | Claim and complete assigned remediation tasks. |
| Team Head | Reassign active tasks inside the fixed relevant team. |
| Process Owner | View all state and cancel an active process with a reason. |

Unauthorized attempts append `UnauthorizedActionRejected` where a process reference is available,
then return `403` at the API boundary.

## Idempotency

Claim, completion, correlation, remediation completion, and parent-resume operations require
expected version plus correlation/causation identifiers. Equivalent repeated terminal commands
return the existing result without duplicate tasks, attempts, cycles, or resume effects. Conflicting
repeats and cancelled/invalid states return `409`.

## Migration

The opaque `InspectionConnector` remains during transition. A `DurableInspectionProcessPort` is
introduced behind the prerequisite boundary. The local Key Handover adapter uses durable
request-or-correlation when enabled; otherwise it preserves existing synthetic connector behavior.
Task 003 tests remain valid and no migration of existing synthetic Key Handover data is required.

## Consequences

Inspection/remediation state, tasks, audit history, and pending parent resumes become durable and
restart-safe without making Key Handover a generic workflow host. The local adapter gains one
focused process/task snapshot and no production integration.

## Out of scope

- Generic workflow DSL or designer;
- generic assignment engine;
- configurable SLA engine;
- delegation engine;
- organization studio;
- generic Work Management Layer, dashboards, or studios;
- production connectors;
- file upload or evidence payload storage;
- external message broker; and
- distributed event bus.

## Acceptance criteria

This ADR is complete when implementation can demonstrate:

1. inspection state lives in the separate aggregate;
2. the business key prevents duplicate active processes/tasks;
3. the minimum task fields are durable;
4. remediation cycles and attempts are immutable history;
5. a valid passed attempt emits the idempotent parent-resume contract;
6. retries and restarts do not duplicate business effects;
7. mandatory audit events carry shared metadata;
8. application-service authorization enforces the four roles; and
9. all listed out-of-scope platform capabilities remain deferred.
