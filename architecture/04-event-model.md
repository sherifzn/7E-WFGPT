# Initial Event Model

## Workflow events

- WorkflowInstanceCreated
- WorkflowStarted
- ActivityScheduled
- ActivityStarted
- ActivityCompleted
- ActivityFailed
- WorkflowWaiting
- WorkflowResumed
- WorkflowCompleted
- WorkflowCancelled
- ChildWorkflowStarted
- ChildWorkflowCorrelated
- ChildWorkflowCompleted
- PrerequisiteMissing
- PrerequisiteSatisfied

## Task events

- TaskCreated
- TaskQueued
- TaskAssigned
- TaskClaimed
- TaskAccepted
- TaskStarted
- TaskPaused
- TaskResumed
- TaskDelegated
- TaskReassigned
- TaskReturned
- TaskCompleted
- TaskCancelled
- TaskEscalated

## SLA events

- SlaRegistered
- SlaWarningReached
- SlaBreached
- SlaPaused
- SlaResumed
- SlaExtended

## Decision events

- DecisionRequested
- DecisionEvaluated
- DecisionExceptionRequired
- DecisionRecommended
- DecisionAutomaticallyApplied
- DecisionOverridden

## Required event envelope

Every event must include:

- eventId
- eventType
- eventVersion
- occurredAt
- correlationId
- causationId
- workflowInstanceId when applicable
- taskInstanceId when applicable
- actorType
- actorId or service identity
- tenant or business-domain scope
- dataClassification
- policy versions used
- trace identifier

Sensitive payloads should be referenced, not embedded.
