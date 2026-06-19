# Key Handover Slice Notes

## Scope

Task 003 implements the Property Key Handover vertical slice only. It proves the first executable architecture path with a pure domain service, explicit ports, synthetic adapters, in-memory durable test state, and integration tests.

## Affected modules

- `domain` adds the Key Handover application service, typed slice identifiers, request state, human-task policy records, branch outcomes, final decision model, audit envelope, and connector ports.
- `adapters` adds synthetic in-memory adapters for property, owner identity, inspection, finance, legal, notification, evidence, decision, authorization, audit, clock, delegation, and state persistence.
- `adapters` tests exercise the complete slice with synthetic data, mocked integrations, restart behavior, retry behavior, authorization checks, and deterministic outcomes.

## Implemented flow

1. A synthetic front-door submission validates property and owner references through typed connector ports.
2. Inspection status is checked through the inspection connector.
3. A missing inspection correlates with an existing child workflow or starts exactly one child workflow using a deterministic idempotency key.
4. Handover, Finance, and Legal branches are represented as logically parallel human tasks.
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
- Task view, claim, completion, and emergency reassignment require authorization checks.
- Segregation of duties is checked at claim and completion.
- Emergency reassignment requires a Team Head permission, reason, future expiry, and audit event.
- Delegation checks are modeled so delegation cannot increase authority.

## Durability and recovery

- `KeyHandoverStateStore` is a persistence port, not a production database choice.
- The synthetic adapter stores state in memory for tests and enforces optimistic state-version checks.
- Restart behavior is tested by reconstructing the application service over the same state store.
- `DomainVersion stateVersion` is mandatory on state and expected by state-changing commands to support future optimistic concurrency and event ordering.
- Notification failure after authorization is recorded as `NOTIFICATION_FAILED`; persistence and retry orchestration remain production ADR topics.

## Tests added

- Valid submission opens configured human tasks.
- Missing inspection starts exactly one idempotent child workflow.
- Existing inspection child workflow is correlated rather than recreated.
- Branches complete in different orders and final decision waits for all branches.
- GREEN, AMBER, and RED outcomes produce deterministic actions.
- Duplicate completion is idempotent and conflicting duplicate completion is rejected and audited.
- State versions protect optimistic concurrency.
- Service restart continues from the same durable store.
- Transient connector failure retries and then exhausts deterministically.
- Notification failure is surfaced and recorded after authorization state changes.
- Unauthorized task access, SLA warnings/breaches, emergency reassignment, and segregation of duties are enforced.
- Delegation does not increase authority.
- Audit sink failure is not swallowed and leaves state recoverable for test-only inspection.

## Dependency impact

No new external dependency was introduced for Task 003. The slice uses the existing Java 21, Maven, JUnit, and Spotless setup from Task 001 and Task 002.

## Known limitations

- This is not a generic workflow engine, low-code designer, REST API, production database integration, production connector implementation, AI gateway, or cloud infrastructure.
- The synthetic state store is test-only and does not provide transactional coupling between state persistence and audit persistence.
- Timer scheduling, durable queueing, worker leasing, distributed locks, compensation, and production retry backoff are out of scope.
- Business thresholds, role names, team names, SLA values, authority limits, and routing rules remain configuration or BRE/DMN concerns and are not hard-coded into domain logic.
- The deterministic decision service is a slice placeholder behind `DecisionService`; BRE/DMN ownership requires an ADR before production implementation.

## Unresolved ADRs

- Production workflow runtime engine and durable state-transition model.
- Runtime database, transactional outbox, and immutable audit store.
- BRE/DMN engine and decision policy publication model.
- Production Oracle connector pattern and financial payload classification.
- Identity federation, authorization policy engine, and delegation authority model.
- Evidence/document storage and retention model.
- Notification provider and retry/dead-letter strategy.
- Hosting, observability, secrets management, and disaster recovery.
