# Domain Boundaries

## Process Definition

Entities:

- WorkflowTemplate
- WorkflowDefinition
- WorkflowVersion
- FormDefinition
- SubprocessReference
- PublicationValidation
- EnvironmentBinding

## Workflow Runtime

Entities:

- WorkflowInstance
- ActivityInstance
- ChildWorkflowLink
- Timer
- CorrelationKey
- CompensationRecord

## Work Management

Entities:

- TaskType
- TaskInstance
- AssignmentPolicy
- WorkloadPolicy
- SlaPolicy
- EscalationPolicy
- Delegation
- EmergencyOverride
- Team
- TeamMembership
- Skill
- WorkingCalendar

## Authorization

Entities:

- Role
- Permission
- Scope
- AttributeRule
- AuthorityLimit
- SegregationOfDutiesRule

## Decisions

Entities:

- DecisionDefinition
- DecisionVersion
- DecisionInput
- DecisionOutput
- DecisionTrace
- ExceptionPolicy

## Prerequisites

Entities:

- Capability
- PrerequisitePolicy
- Dependency
- DependencyEvaluation
- ChildWorkflowTrigger
- CorrelationPolicy

## Knowledge

Entities:

- RawCaseRecord
- KnowledgeCandidate
- ApprovedKnowledgeRecord
- KnowledgeQualityScore
- KnowledgeAccessPolicy
- KnowledgeExpiry
- DecisionSupportResult

## AI Governance

Entities:

- AiTaskDefinition
- ModelRoute
- PromptVersion
- ToolAllowlist
- OutputSchema
- CostPolicy
- HumanFallbackPolicy
- AutonomyPolicy
- ModelEvaluation
