# First Vertical Slice: Property Key Handover

## Goal

Prove the architecture with one complete process, using mocked enterprise connectors and synthetic data.

## Flow

1. Front Desk submits Key Handover Request.
2. Platform validates owner and property references.
3. Platform checks whether a valid inspection exists.
4. If inspection is missing:
   - correlate with an existing Inspection workflow, or
   - start one child Inspection workflow using an idempotency key.
5. Handover, Finance, and Legal clearances run in parallel.
6. Each clearance is represented by a configurable human task:
   - team and role eligibility
   - manual or automatic assignment
   - task weight
   - assignment and completion SLA
   - alerts and escalation
   - delegation and emergency reassignment
7. Finance obtains a synthetic outstanding-amount result through a mocked Oracle connector.
8. All branches return GREEN, AMBER, or RED with evidence references.
9. A deterministic decision evaluates the combined outcome.
10. Approved cases create a Key Release Authorization and notification.
11. Every transition emits an audit event.

## Out of scope

- production Oracle access
- production identity integration
- actual AI model calls
- visual low-code designer
- autonomous decisions
- real customer data
- cloud-specific infrastructure

## Acceptance criteria

- parallel branches execute correctly
- missing inspection produces one child workflow only
- duplicate initiation is prevented by business key
- assignment policy is configurable
- SLA warnings and breaches are testable with a controllable clock
- Team Head reassignment is authorized and audited
- delegation never increases authority
- final decision is deterministic and traceable
- state survives application restart in an integration test
- all external systems are represented by interfaces and mocks
