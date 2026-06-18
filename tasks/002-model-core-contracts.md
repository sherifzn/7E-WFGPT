# Task 002 — Model Core Contracts

## Objective

Implement immutable domain contracts only. Do not implement a workflow engine.

## Required contracts

- WorkflowDefinition
- WorkflowVersion
- WorkflowInstance
- ActivityDefinition
- ActivityInstance
- TaskType
- TaskInstance
- AssignmentPolicy
- SlaPolicy
- EscalationPolicy
- Delegation
- Team
- TeamMembership
- PrerequisitePolicy
- DecisionRequest
- DecisionResult
- AuditEvent envelope

## Requirements

- explicit identifiers
- version fields
- data-classification field
- validation rules
- no framework annotations in the pure domain module
- serialization schemas in a separate adapters module
- unit tests for invariants
- architecture tests proving domain isolation

## Prohibited

- database implementation
- REST controller
- Oracle code
- AI code
- hard-coded business thresholds
