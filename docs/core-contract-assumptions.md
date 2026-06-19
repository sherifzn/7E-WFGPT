# Core Contract Assumptions and Limits

## Scope

Task 002 adds immutable core domain contracts only. It does not implement workflow execution, persistence, REST controllers, Oracle integration, identity integration, AI functionality, or production infrastructure.

## Module boundaries

- `domain` contains pure Java domain records, typed identifiers, enums, and constructor invariants.
- `adapters` contains serialization schema resources and schema catalog access. Domain classes do not depend on adapters.
- `domain.common.DataClassification` is the canonical data-classification model. Transport-facing modules may depend inward on this enum, but the domain module must not depend on transport, adapter, framework, or backend modules.
- `architecture-tests` proves domain isolation and guards against forbidden production integration markers.

## Contract assumptions

- Java records are used for immutable contracts under the current Java-or-Kotlin constraint. The primary backend language still requires an ADR.
- Contract schemas are JSON Schema resources only. Runtime JSON serialization libraries are not introduced in this task.
- Data classification travels on domain contracts that represent definitions, instances, policies, decisions, and audit events.
- Sensitive decision inputs and outputs are modeled as reference keys, not embedded payloads.
- SLA and assignment policy contracts hold references and durations only; production thresholds and routing logic remain outside domain code and belong in BRE/DMN or configuration approved by later ADRs.
- Runtime instances carry a mandatory positive `stateVersion` for future optimistic concurrency and event ordering. This task does not implement persistence or state-transition logic.

## Unresolved ADRs

- Primary backend language.
- Event-contract and serialization strategy.
- Core workflow runtime engine.
- BRE and DMN implementation.
- Runtime database and event bus.
- Oracle integration pattern.
- Identity federation implementation.
- AI Gateway and model routing.
- Observability and audit storage.
- Secrets and key management.
- Hosting and deployment approach.

## Known limitations

- The contracts define structure and invariants, not state transitions.
- JSON schemas currently cover `WorkflowDefinition`, `WorkflowInstance`, `ActivityInstance`, `TaskInstance`, and `AuditEvent`; additional schemas should be added as adapters mature.
- Domain IDs are typed string wrappers; final ID format policy remains unresolved.
