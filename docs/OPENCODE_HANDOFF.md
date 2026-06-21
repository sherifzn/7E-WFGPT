# OpenCode Development Handoff

## Project

Repository:

`/Users/sherifzn/Development/7E-WFGPT`

Current local branch:

`codex/task-003-key-handover`

Latest local commit:

`ca78247 fix(inspection): enforce task roles and complete audit history`

Last confirmed remote commit:

`93397ab feat(key-handover): add activity history controls and notification recovery testing`

The local branch is 11 commits ahead of GitHub.

## Critical first action

Before changing anything, run:

```bash
git status --short
git log --oneline --decorate -10
git diff
git diff --cached
```

Do not reset, clean, stash, or discard files.

## Development operating model

Use this workflow:

```text
Requirement
→ Develop locally
→ Run focused tests
→ Manual browser acceptance
→ Fix defects
→ Run full validation
→ Commit locally
→ Push only after product approval
```

Do not push to GitHub automatically.

Do not merge automatically.

Do not rewrite Git history.

## Product architecture principles

### AI usage

- AI is primarily a design-time assistant.
- Runtime business execution remains deterministic.
- Runtime AI is reserved for unstructured or exceptional work.
- AI must not make unrestricted production decisions.

### Workflow architecture

- Business processes define which activities must occur.
- Work-management capabilities define assignment, authority, SLA, escalation, and delegation.
- Do not build the full configurable Work Management Layer unless explicitly requested.
- Generalize capabilities only after repeated business-process requirements prove the need.

### Security

- Use synthetic data only.
- Do not add production credentials.
- Do not connect directly to Oracle tables.
- Oracle access must remain behind connector interfaces.
- Backend authorization is authoritative.
- Frontend visibility must not replace backend authorization.
- Do not expose sensitive data or stack traces.
- Preserve immutable audit history.
- Preserve correlation and causation metadata.
- Commands must be idempotent where applicable.

## Completed milestone: Key Handover

The Key Handover vertical slice currently supports:

- Handover, Finance, and Legal clearances
- inspection prerequisite handling
- task claiming and reassignment
- deterministic GREEN, AMBER, and RED outcomes
- exception approval
- hold management
- controlled remediation and resume
- rejection and cancellation from hold
- authorization generation
- notification failure simulation
- notification retry
- Activity History expand/collapse
- local durable snapshots
- backend authorization
- audit events
- duplicate-command handling

Important commits include:

- `abb1ebe` exception approval workflow
- `0fdef7d` ADR-019 accepted
- `dd2c3e5` hold policy domain model
- `ef3e0e9` runtime and persistence integration
- `f1a1466` API and UI hold management
- `e971102` legacy hold initialization
- `93397ab` Activity History controls and notification recovery

## Completed milestone: Inspection Request and Remediation

The Inspection vertical slice is complete and validated. It supports:

- ADR-020 accepted
- durable `InspectionProcess` aggregate
- `InspectionApplicationService`
- local snapshot persistence with optimistic versioning
- pending parent-resume event persistence and recovery
- automatic parent Key Handover resume via `InspectionPrerequisiteSatisfiedHandler`
- Finance and Legal branch preservation on parent resume
- Inspection HTTP API (`/api/inspections`)
- Inspection frontend workspace
- inspection/reinspection role enforcement for Inspection Officer
- remediation role enforcement for Remediation Officer
- Process Owner cancel authorization
- durable inspection audit history
- remediation and reinspection flow
- manual and restart-recovery browser acceptance

Important commits in order:

- `1cfb485` local snapshot persistence
- `1a8e615` durable inspection aggregate contract (ADR-020)
- `8dbbe94` inspection application service
- `a56d591` parent handover resume handler
- `2c3951c` preserve clearance branches on parent resume
- `1e6895a` complete inspection remediation workflow
- `7189cef` create and display inspection from handover request
- `8ea674f` complete handover child-process integration
- `eb5922e` initialize Finance and Legal before inspection
- `ca78247` enforce task roles and complete audit history

### Inspection architecture decision

ADR:

`architecture/adr-020-inspection-remediation-runtime-contract.md`

Status:

Accepted

Selected architecture:

- Separate durable Inspection Process aggregate
- Own inspection process ID
- Business-key uniqueness
- Inspection attempts
- Remediation cycles
- Fixed tasks
- Audit history
- Pending parent-resume events

Business key:

```text
propertyReference
+ inspectionType
+ keyHandoverRequestReference
```

Parent-resume mechanism:

```text
Inspection passes
→ emit InspectionPrerequisiteSatisfied
→ persist pending event
→ local idempotent handler invokes Key Handover resume command
→ resume only Handover branch
→ preserve Finance and Legal
→ mark event handled after successful parent update
```

Persistence direction:

- Reuse the existing atomic local snapshot pattern.
- Use optimistic versioning.
- Recover pending parent-resume events after restart.
- Do not introduce a database or broker yet.

Migration direction:

- Introduce durable inspection behavior behind the current inspection port.
- Preserve the existing opaque connector behavior during transition.
- Do not migrate existing Key Handover snapshots.

### Inspection role model

- **Inspection Officer**: claim and complete initial inspection and reinspection work.
- **Remediation Officer**: claim and complete remediation work.
- **Process Owner**: cancel active inspection processes, retry transient parent-resume failures, view process and history.
- Backend authorization is authoritative; frontend controls reflect the selected identity.

### Inspection commands

Implemented application commands:

- create or correlate inspection process
- claim inspection task
- complete inspection as PASSED
- complete inspection as FAILED
- claim remediation task
- complete remediation
- claim reinspection task
- complete reinspection
- cancel active inspection process

Implemented command behavior:

- equivalent terminal repeats return existing state
- conflicting repeats are rejected
- stale expected versions are rejected
- unauthorized roles receive 403 and are audited

### Inspection audit history

The inspection detail history displays persisted lifecycle events in chronological order:

- InspectionRequested
- InspectionTaskCreated
- InspectionTaskClaimed
- InspectionCompleted
- InspectionPassed / InspectionFailed
- RemediationRequired
- RemediationTaskCreated
- RemediationTaskClaimed
- RemediationCompleted
- ReinspectionRequested
- ReinspectionTaskCreated
- ParentWorkflowResumeRequested
- ParentWorkflowResumed
- InspectionCancelled
- UnauthorizedActionRejected
- ParentResumeFailed, where applicable

## Latest focused validation

```text
mvn test
120 tests passed
0 failures
```

```text
npx vitest run
13 tests passed
0 failures
```

```text
npm run lint
0 warnings
0 errors
```

```text
npm run build
Production build succeeded
```

## Next development slice

No active slice is defined. The Inspection Request and Remediation milestone is complete. Await product approval before pushing to GitHub.

## Explicitly deferred

Do not implement unless separately requested:

- generic workflow DSL
- generic workflow designer
- full Work Management Layer
- configurable assignment engine
- configurable SLA engine
- delegation engine
- organization studio
- external event broker
- production persistence
- production connectors
- real identity integration
- file upload
- AI knowledge base
- autonomous decisions
- cloud deployment

## Testing policy

Prefer focused tests during implementation.

For inspection domain work:

```bash
mvn -pl domain test
```

For persistence work, run the affected adapter module and architecture tests.

Before a product checkpoint, run:

- frontend formatting
- frontend lint
- frontend tests
- frontend production build
- backend/API tests
- domain/adapters tests
- architecture tests

## Git behavior

The sandbox may be unable to create:

- `.git/index.lock`
- `.git/refs/heads/...lock`

When blocked:

- preserve the working tree
- report the exact `git add` and `git commit` commands
- do not troubleshoot permissions
- do not reset or discard changes

Normal macOS Terminal can perform the final commit.

## Completion reporting format

For each development slice, report only:

- commit SHA or Git blocker
- files changed
- behavior implemented
- authorization behavior
- idempotency behavior
- persistence/restart behavior when applicable
- tests and build results
- exact browser acceptance steps when applicable
- confirmation that GitHub was not updated
