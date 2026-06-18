# Task 003 — Implement the Key Handover Vertical Slice

Begin only after Tasks 001 and 002 are reviewed.

## Scope

Implement the vertical slice described in `docs/first-vertical-slice.md`.

## Required interfaces

- PropertyConnector
- OwnerIdentityConnector
- InspectionConnector
- FinanceConnector
- LegalConnector
- NotificationConnector
- EvidenceStore
- DecisionService
- AuditSink
- Clock

## Required behavior

- Handover, Finance, and Legal branches execute in parallel.
- Missing inspection starts or correlates with one child workflow.
- Finance connector returns synthetic Oracle-like results.
- Human tasks use configurable assignment and SLA policies.
- Final decision is deterministic.
- All state transitions emit audit events.
- Failure and retry behavior is explicit.
- Integration tests use a controllable clock and synthetic fixtures.

## Security

- no direct database table exposure
- no sensitive payloads in logs
- authorization checks for task access and reassignment
- Team Head emergency reassignment must require reason and expiry
- segregation-of-duties checks at assignment and completion

## Deliverable

Draft pull request with tests, design notes, and unresolved ADRs.
