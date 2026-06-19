# Key Handover Slice Notes

## Scope

Task 003 implements the Property Key Handover vertical slice only. It proves the first executable architecture path with a pure domain service, explicit ports, synthetic adapters, recoverable pending-audit transitions, and integration tests.

## Affected modules

- `domain` adds the Key Handover application service, typed slice identifiers, request state, human-task policy records, branch outcomes, final decision model, audit envelope, and connector ports.
- `adapters` adds synthetic in-memory adapters for property, owner identity, inspection, finance, legal, notification, evidence, decision, authorization, audit, clock, delegation, and state persistence.
- `adapters` tests exercise the complete slice with synthetic data, mocked integrations, restart behavior, retry behavior, authorization checks, and deterministic outcomes.

## Implemented flow

1. A synthetic front-door submission validates property and owner references through typed connector ports.
2. Inspection status is checked through the inspection connector.
3. A missing inspection correlates with an existing child workflow or starts exactly one child workflow using a deterministic idempotency key, then persists `WAITING_FOR_INSPECTION` without opening clearance work.
4. An explicit, idempotent inspection-available command moves the request to `CLEARANCE_IN_PROGRESS` and opens Handover, Finance, and Legal branches.
5. Human tasks carry configurable eligibility, assignment mode, assignment policy reference, SLA policy reference, escalation policy reference, task weight, and authority scopes.
6. Finance returns a synthetic outstanding-amount result through `FinanceConnector`; no direct Oracle access exists.
7. Branches produce GREEN, AMBER, or RED outcomes with evidence references only.
8. The deterministic decision service authorizes release only when all branches are GREEN, holds on any RED, and routes AMBER/mixed results to exception approval.
9. Approved decisions create a key-release authorization and send a synthetic notification with an idempotency key.
10. State-changing transitions emit audit records with correlation, causation, actor, state version, and evidence references.

## Security and privacy impact

- All data in tests and adapters is synthetic.
- External systems are represented only by ports and synthetic adapters.
- No production identity provider, Oracle connection, document store, AI model, network integration, credentials, customer data, legal evidence, financial records, or identity documents are used.
- Evidence is represented by references rather than payloads.
- Submission uses a dedicated `SUBMIT_REQUEST` permission; task view, claim, completion, normal reassignment, and emergency reassignment require their respective permissions.
- Claim, completion, reassignment, and emergency reassignment enforce policy eligibility, authority scopes, assignment mode, assigned actor, and segregation of duties where applicable.
- Emergency reassignment requires permission, new-assignee eligibility and authority, a reason, future expiry, and audit metadata containing reason, expiry, previous assignee, and new assignee.
- Delegation checks are modeled so delegation cannot increase authority.

## Durability and recovery

- `KeyHandoverStateStore` is a persistence port, not a production database choice. It commits each state transition together with pending immutable audit records.
- The synthetic in-memory store is fast-test-only. A clearly labeled test-only path-backed snapshot adapter proves reconstruction from the same test storage location in-process; it is not production durability.
- Pending audits remain recoverable if sink delivery fails and are retried idempotently after reconstruction.
- `DomainVersion stateVersion` is mandatory on state and expected by state-changing commands to support future optimistic concurrency and event ordering.
- Notification idempotency uses `IdempotencyKey`. Delivery status, attempt count, and a non-sensitive failure reference are persisted. Explicit recovery retries notification delivery without duplicate successful sends.
- Connector retries use the configured attempt count and retry backoff through a scheduler port; deterministic tests never sleep.

## Tests added

- Valid submission opens configured human tasks only when inspection is available.
- The inspection barrier blocks task operations, SLA evaluation, and final decision work until an idempotent inspection-resume command succeeds.
- Missing inspection starts exactly one idempotent child workflow.
- Existing inspection child workflow is correlated rather than recreated.
- Branches complete in different orders and final decision waits for all branches.
- GREEN, AMBER, and RED outcomes produce deterministic actions.
- Duplicate completion is idempotent and conflicting duplicate completion is rejected and audited.
- State versions protect optimistic concurrency.
- Test-only path-backed state reconstruction continues from the same test storage location.
- Transient connector failures use configured backoff requests, then exhaust deterministically; validation and authorization failures are not retried.
- Notification failure, retry, delivery, and retry exhaustion have explicit durable audit behavior.
- Unauthorized and stale finance completion cannot trigger connector side effects; legal and evidence ports are exercised through the application service.
- SLA warnings/breaches audit only newly recorded thresholds; no-op evaluation does not advance state version.
- Delegation does not increase authority.
- Audit sink failure is not swallowed and leaves state recoverable for test-only inspection.

## Dependency impact

No new external dependency was introduced for Task 003. The slice uses the existing Java 21, Maven, JUnit, and Spotless setup from Task 001 and Task 002.

## Known limitations

- This is not a generic workflow engine, low-code designer, REST API, production database integration, production connector implementation, AI gateway, or cloud infrastructure.
- The test-only path-backed adapter is same-process reconstruction coverage, not crash-safe, multi-process, or production persistence.
- No production transactional outbox, durable queue, audit delivery worker, timer scheduling, worker leasing, distributed lock, compensation, or retry scheduler has been selected.
- Business thresholds, role names, team names, SLA values, authority limits, and routing rules remain configuration or BRE/DMN concerns and are not hard-coded into domain logic.
- The deterministic decision service is a slice placeholder behind `DecisionService`; BRE/DMN ownership requires an ADR before production implementation.

## Unresolved ADRs

- Production workflow runtime engine and durable state-transition model.
- Runtime database, transactional outbox, immutable audit store, and audit-delivery retry ownership.
- BRE/DMN engine and decision policy publication model.
- Production Oracle connector pattern and financial payload classification.
- Identity federation, authorization policy engine, and delegation authority model.
- Evidence/document storage and retention model.
- Notification provider, durable retry scheduler, and dead-letter strategy.
- Hosting, observability, secrets management, and disaster recovery.
