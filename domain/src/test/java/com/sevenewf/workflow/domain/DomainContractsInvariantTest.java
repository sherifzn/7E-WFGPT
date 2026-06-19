package com.sevenewf.workflow.domain;

import com.sevenewf.workflow.domain.audit.AuditEvent;
import com.sevenewf.workflow.domain.audit.AuditEventId;
import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.ActorType;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.TenantScope;
import com.sevenewf.workflow.domain.common.TraceId;
import com.sevenewf.workflow.domain.decision.DecisionDefinitionId;
import com.sevenewf.workflow.domain.decision.DecisionOutcome;
import com.sevenewf.workflow.domain.decision.DecisionRequest;
import com.sevenewf.workflow.domain.decision.DecisionRequestId;
import com.sevenewf.workflow.domain.decision.DecisionResult;
import com.sevenewf.workflow.domain.decision.DecisionResultId;
import com.sevenewf.workflow.domain.prerequisite.PrerequisitePolicy;
import com.sevenewf.workflow.domain.prerequisite.PrerequisitePolicyId;
import com.sevenewf.workflow.domain.work.AssignmentPolicy;
import com.sevenewf.workflow.domain.work.AssignmentPolicyId;
import com.sevenewf.workflow.domain.work.Delegation;
import com.sevenewf.workflow.domain.work.DelegationId;
import com.sevenewf.workflow.domain.work.EscalationPolicy;
import com.sevenewf.workflow.domain.work.EscalationPolicyId;
import com.sevenewf.workflow.domain.work.SlaPolicy;
import com.sevenewf.workflow.domain.work.SlaPolicyId;
import com.sevenewf.workflow.domain.work.TaskInstance;
import com.sevenewf.workflow.domain.work.TaskInstanceId;
import com.sevenewf.workflow.domain.work.TaskInstanceStatus;
import com.sevenewf.workflow.domain.work.TaskType;
import com.sevenewf.workflow.domain.work.TaskTypeId;
import com.sevenewf.workflow.domain.work.Team;
import com.sevenewf.workflow.domain.work.TeamId;
import com.sevenewf.workflow.domain.work.TeamMembership;
import com.sevenewf.workflow.domain.work.TeamMembershipId;
import com.sevenewf.workflow.domain.work.TeamMembershipStatus;
import com.sevenewf.workflow.domain.workflow.ActivityDefinition;
import com.sevenewf.workflow.domain.workflow.ActivityDefinitionId;
import com.sevenewf.workflow.domain.workflow.ActivityInstance;
import com.sevenewf.workflow.domain.workflow.ActivityInstanceId;
import com.sevenewf.workflow.domain.workflow.ActivityInstanceStatus;
import com.sevenewf.workflow.domain.workflow.WorkflowDefinition;
import com.sevenewf.workflow.domain.workflow.WorkflowDefinitionId;
import com.sevenewf.workflow.domain.workflow.WorkflowInstance;
import com.sevenewf.workflow.domain.workflow.WorkflowInstanceId;
import com.sevenewf.workflow.domain.workflow.WorkflowInstanceStatus;
import com.sevenewf.workflow.domain.workflow.WorkflowVersion;
import com.sevenewf.workflow.domain.workflow.WorkflowVersionId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

final class DomainContractsInvariantTest {
  private static final Instant SYNTHETIC_TIME = Instant.parse("2026-01-01T00:00:00Z");
  private static final DataClassification CLASSIFICATION = DataClassification.INTERNAL;

  @Test
  void typedIdentifiersAndVersionsRejectBlankOrNonPositiveValues() {
    Assertions.assertThrows(IllegalArgumentException.class, () -> new WorkflowDefinitionId(" "));
    Assertions.assertThrows(IllegalArgumentException.class, () -> new DomainVersion(0));
  }

  @Test
  void workflowDefinitionRequiresAtLeastOneActivityAndClassification() {
    ActivityDefinition activity = activityDefinition();

    WorkflowDefinition definition =
        new WorkflowDefinition(
            workflowDefinitionId(),
            version(),
            "synthetic.workflow",
            "Synthetic Workflow",
            CLASSIFICATION,
            List.of(activity));

    Assertions.assertEquals(List.of(activity), definition.activities());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkflowDefinition(
                workflowDefinitionId(),
                version(),
                "synthetic.workflow",
                "Synthetic Workflow",
                CLASSIFICATION,
                List.of()));
    Assertions.assertThrows(
        NullPointerException.class,
        () ->
            new WorkflowDefinition(
                workflowDefinitionId(),
                version(),
                "synthetic.workflow",
                "Synthetic Workflow",
                null,
                List.of(activity)));
  }

  @Test
  void workflowRuntimeContractsRequireVersionedDefinitionAndCorrelation() {
    WorkflowVersion workflowVersion =
        new WorkflowVersion(
            workflowVersionId(), workflowDefinitionId(), version(), SYNTHETIC_TIME, CLASSIFICATION);
    WorkflowInstance instance =
        new WorkflowInstance(
            workflowInstanceId(),
            version(),
            workflowVersion.id(),
            WorkflowInstanceStatus.CREATED,
            tenantScope(),
            correlationId(),
            SYNTHETIC_TIME,
            CLASSIFICATION);
    ActivityInstance activityInstance =
        new ActivityInstance(
            activityInstanceId(),
            version(),
            instance.id(),
            activityDefinition().id(),
            ActivityInstanceStatus.SCHEDULED,
            SYNTHETIC_TIME,
            CLASSIFICATION);

    Assertions.assertEquals(workflowVersion.id(), instance.workflowVersionId());
    Assertions.assertEquals(version(), instance.stateVersion());
    Assertions.assertEquals(instance.id(), activityInstance.workflowInstanceId());
    Assertions.assertEquals(version(), activityInstance.stateVersion());
  }

  @Test
  void taskAndPolicyContractsRequireTypedReferencesAndRules() {
    TaskType taskType =
        new TaskType(taskTypeId(), version(), "synthetic.task", "Synthetic Task", CLASSIFICATION);
    TaskInstance taskInstance =
        new TaskInstance(
            taskInstanceId(),
            version(),
            taskType.id(),
            activityInstanceId(),
            TaskInstanceStatus.CREATED,
            Optional.empty(),
            SYNTHETIC_TIME,
            CLASSIFICATION);
    AssignmentPolicy assignmentPolicy =
        new AssignmentPolicy(
            assignmentPolicyId(),
            version(),
            taskType.id(),
            List.of("synthetic.assignment.rule"),
            CLASSIFICATION);
    EscalationPolicy escalationPolicy =
        new EscalationPolicy(
            escalationPolicyId(),
            version(),
            taskType.id(),
            List.of("synthetic.escalation.rule"),
            CLASSIFICATION);

    Assertions.assertEquals(taskType.id(), taskInstance.taskTypeId());
    Assertions.assertEquals(version(), taskInstance.stateVersion());
    Assertions.assertEquals(taskType.id(), assignmentPolicy.taskTypeId());
    Assertions.assertEquals(taskType.id(), escalationPolicy.taskTypeId());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new AssignmentPolicy(
                assignmentPolicyId(), version(), taskType.id(), List.of(), CLASSIFICATION));
  }

  @Test
  void slaPolicyRequiresBreachWindowAfterWarningWindow() {
    Duration warningAfter = Duration.ofMinutes(5);
    Duration breachAfter = Duration.ofMinutes(10);

    SlaPolicy policy =
        new SlaPolicy(
            slaPolicyId(), version(), taskTypeId(), warningAfter, breachAfter, CLASSIFICATION);

    Assertions.assertEquals(breachAfter, policy.breachAfter());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new SlaPolicy(
                slaPolicyId(), version(), taskTypeId(), breachAfter, warningAfter, CLASSIFICATION));
  }

  @Test
  void runtimeInstancesRequirePositiveStateVersions() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new WorkflowInstance(
                workflowInstanceId(),
                new DomainVersion(0),
                workflowVersionId(),
                WorkflowInstanceStatus.CREATED,
                tenantScope(),
                correlationId(),
                SYNTHETIC_TIME,
                CLASSIFICATION));
  }

  @Test
  void delegationCannotIncreaseAuthorityBySelfDelegatingOrUsingEmptyScope() {
    ActorId delegatorId = new ActorId("synthetic-actor-001");
    ActorId delegateId = new ActorId("synthetic-actor-002");

    Delegation delegation =
        new Delegation(
            delegationId(),
            version(),
            delegatorId,
            delegateId,
            SYNTHETIC_TIME,
            SYNTHETIC_TIME.plus(Duration.ofMinutes(30)),
            List.of("synthetic.authority.scope"),
            CLASSIFICATION);

    Assertions.assertEquals(delegateId, delegation.delegateId());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new Delegation(
                delegationId(),
                version(),
                delegatorId,
                delegatorId,
                SYNTHETIC_TIME,
                SYNTHETIC_TIME.plus(Duration.ofMinutes(30)),
                List.of("synthetic.authority.scope"),
                CLASSIFICATION));
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new Delegation(
                delegationId(),
                version(),
                delegatorId,
                delegateId,
                SYNTHETIC_TIME,
                SYNTHETIC_TIME,
                List.of("synthetic.authority.scope"),
                CLASSIFICATION));
  }

  @Test
  void teamAndMembershipContractsUseTypedMembershipReferences() {
    Team team = new Team(teamId(), version(), "synthetic.team", "Synthetic Team", CLASSIFICATION);
    TeamMembership membership =
        new TeamMembership(
            teamMembershipId(),
            version(),
            team.id(),
            new ActorId("synthetic-member-001"),
            TeamMembershipStatus.ACTIVE,
            SYNTHETIC_TIME,
            CLASSIFICATION);

    Assertions.assertEquals(team.id(), membership.teamId());
  }

  @Test
  void prerequisiteAndDecisionContractsCarryReferencesInsteadOfPayloads() {
    PrerequisitePolicy prerequisitePolicy =
        new PrerequisitePolicy(
            prerequisitePolicyId(),
            version(),
            workflowDefinitionId(),
            List.of("synthetic.capability"),
            CLASSIFICATION);
    DecisionRequest request =
        new DecisionRequest(
            decisionRequestId(),
            decisionDefinitionId(),
            version(),
            workflowInstanceId(),
            correlationId(),
            List.of("synthetic.input.reference"),
            SYNTHETIC_TIME,
            CLASSIFICATION);
    DecisionResult result =
        new DecisionResult(
            decisionResultId(),
            request.id(),
            version(),
            DecisionOutcome.RECOMMENDED,
            List.of("synthetic.output.reference"),
            SYNTHETIC_TIME,
            CLASSIFICATION);

    Assertions.assertEquals(workflowDefinitionId(), prerequisitePolicy.workflowDefinitionId());
    Assertions.assertEquals(request.id(), result.decisionRequestId());
  }

  @Test
  void auditEventEnvelopeRequiresPolicyVersionsAndTraceability() {
    AuditEvent event =
        new AuditEvent(
            auditEventId(),
            "SyntheticEventRecorded",
            version(),
            SYNTHETIC_TIME,
            correlationId(),
            causationId(),
            Optional.of(workflowInstanceId()),
            Optional.of(taskInstanceId()),
            ActorType.SERVICE,
            new ActorId("synthetic-service"),
            tenantScope(),
            CLASSIFICATION,
            List.of("synthetic-policy:1"),
            traceId());

    Assertions.assertEquals("SyntheticEventRecorded", event.eventType());
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () ->
            new AuditEvent(
                auditEventId(),
                "SyntheticEventRecorded",
                version(),
                SYNTHETIC_TIME,
                correlationId(),
                causationId(),
                Optional.empty(),
                Optional.empty(),
                ActorType.SERVICE,
                new ActorId("synthetic-service"),
                tenantScope(),
                CLASSIFICATION,
                List.of(),
                traceId()));
  }

  private static ActivityDefinition activityDefinition() {
    return new ActivityDefinition(
        new ActivityDefinitionId("synthetic-activity-definition"),
        version(),
        "synthetic.activity",
        "Synthetic Activity",
        CLASSIFICATION);
  }

  private static WorkflowDefinitionId workflowDefinitionId() {
    return new WorkflowDefinitionId("synthetic-workflow-definition");
  }

  private static WorkflowVersionId workflowVersionId() {
    return new WorkflowVersionId("synthetic-workflow-version");
  }

  private static WorkflowInstanceId workflowInstanceId() {
    return new WorkflowInstanceId("synthetic-workflow-instance");
  }

  private static ActivityInstanceId activityInstanceId() {
    return new ActivityInstanceId("synthetic-activity-instance");
  }

  private static TaskTypeId taskTypeId() {
    return new TaskTypeId("synthetic-task-type");
  }

  private static TaskInstanceId taskInstanceId() {
    return new TaskInstanceId("synthetic-task-instance");
  }

  private static AssignmentPolicyId assignmentPolicyId() {
    return new AssignmentPolicyId("synthetic-assignment-policy");
  }

  private static SlaPolicyId slaPolicyId() {
    return new SlaPolicyId("synthetic-sla-policy");
  }

  private static EscalationPolicyId escalationPolicyId() {
    return new EscalationPolicyId("synthetic-escalation-policy");
  }

  private static DelegationId delegationId() {
    return new DelegationId("synthetic-delegation");
  }

  private static TeamId teamId() {
    return new TeamId("synthetic-team");
  }

  private static TeamMembershipId teamMembershipId() {
    return new TeamMembershipId("synthetic-team-membership");
  }

  private static PrerequisitePolicyId prerequisitePolicyId() {
    return new PrerequisitePolicyId("synthetic-prerequisite-policy");
  }

  private static DecisionRequestId decisionRequestId() {
    return new DecisionRequestId("synthetic-decision-request");
  }

  private static DecisionResultId decisionResultId() {
    return new DecisionResultId("synthetic-decision-result");
  }

  private static DecisionDefinitionId decisionDefinitionId() {
    return new DecisionDefinitionId("synthetic-decision-definition");
  }

  private static AuditEventId auditEventId() {
    return new AuditEventId("synthetic-audit-event");
  }

  private static DomainVersion version() {
    return new DomainVersion(1);
  }

  private static CorrelationId correlationId() {
    return new CorrelationId("synthetic-correlation");
  }

  private static CausationId causationId() {
    return new CausationId("synthetic-causation");
  }

  private static TenantScope tenantScope() {
    return new TenantScope("synthetic-scope");
  }

  private static TraceId traceId() {
    return new TraceId("synthetic-trace");
  }
}
