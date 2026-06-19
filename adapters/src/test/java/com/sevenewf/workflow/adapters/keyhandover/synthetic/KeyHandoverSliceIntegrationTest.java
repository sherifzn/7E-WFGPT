package com.sevenewf.workflow.adapters.keyhandover.synthetic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.ControllableClock;
import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.DeterministicDecisionService;
import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.InMemoryDurableKeyHandoverStateStore;
import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.NonExpandingDelegationPolicy;
import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.PermissionAuthorizationService;
import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.RecordingAuditSink;
import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.RecordingNotificationConnector;
import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.SyntheticEvidenceStore;
import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.SyntheticFinanceConnector;
import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.SyntheticInspectionConnector;
import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.SyntheticLegalConnector;
import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.SyntheticOwnerIdentityConnector;
import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.SyntheticPropertyConnector;
import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverApplicationService;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.AuthorizationDeniedException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.ConflictingDuplicateCompletionException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.OptimisticStateConflictException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.RetryExhaustedException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.TransientConnectorException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverState;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.Actor;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.AssignmentMode;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.AuthorizationId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.BranchCompletion;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.BranchStatus;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.BusinessKey;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ChildWorkflowId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ClearanceBranch;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ClearanceOutcome;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EmergencyReassignment;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EvidenceReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.FinanceClearance;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.HumanTaskPolicy;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.InspectionStatus;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverRequestId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverSubmission;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.Permission;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PolicyRef;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PropertyReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.RequestStatus;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.SlicePolicies;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.TeamOrRoleRef;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class KeyHandoverSliceIntegrationTest {
  private static final Instant START = Instant.parse("2026-06-19T08:00:00Z");

  @Test
  void submitValidatesReferencesAndOpensConfiguredHumanTasks() {
    SliceContext context = new SliceContext(new BusinessKey("slice-submit"));

    KeyHandoverState state = context.submit();

    assertEquals(RequestStatus.CLEARANCE_IN_PROGRESS, state.status());
    assertEquals(new DomainVersion(1), state.stateVersion());
    assertEquals(
        Set.of(ClearanceBranch.HANDOVER, ClearanceBranch.FINANCE, ClearanceBranch.LEGAL),
        state.branches().keySet());
    for (ClearanceBranch branch : ClearanceBranch.values()) {
      assertEquals(BranchStatus.OPEN, state.branches().get(branch).status());
      assertEquals(
          context.policies.taskPolicies().get(branch), state.branches().get(branch).taskPolicy());
    }
    assertTrue(context.audit.hasEvent("KeyHandoverSubmitted"));
  }

  @Test
  void missingInspectionCreatesExactlyOneIdempotentChildWorkflow() {
    SliceContext context = new SliceContext(new BusinessKey("slice-inspection-child"));
    context.inspection.setInspectionStatus(new InspectionStatus(false, Optional.empty()));

    KeyHandoverState first = context.submit();
    KeyHandoverState duplicate = context.submit();

    assertEquals(first.requestId(), duplicate.requestId());
    assertEquals(RequestStatus.WAITING_FOR_INSPECTION, first.status());
    assertEquals(1, context.inspection.childWorkflowCount());
    assertTrue(first.inspectionChildWorkflowId().isPresent());
  }

  @Test
  void missingInspectionCorrelatesExistingChildWorkflow() {
    SliceContext context = new SliceContext(new BusinessKey("slice-existing-child"));
    ChildWorkflowId childWorkflowId = new ChildWorkflowId("synthetic-existing-child");
    context.inspection.setInspectionStatus(
        new InspectionStatus(false, Optional.of(childWorkflowId)));

    KeyHandoverState state = context.submit();

    assertEquals(Optional.of(childWorkflowId), state.inspectionChildWorkflowId());
    assertEquals(0, context.inspection.childWorkflowCount());
  }

  @Test
  void branchesCanCompleteInDifferentOrdersAndDecisionWaitsForAllBranches() {
    SliceContext context = new SliceContext(new BusinessKey("slice-parallel-order"));
    KeyHandoverState state = context.submit();

    state =
        context.complete(
            state, ClearanceBranch.LEGAL, ClearanceOutcome.GREEN, context.legalActor());
    assertFalse(state.finalDecision().isPresent());
    state =
        context.complete(
            state, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, context.handoverActor());
    assertFalse(state.finalDecision().isPresent());
    state = context.completeFinance(state, context.financeActor());

    assertEquals(RequestStatus.AUTHORIZED, state.status());
    assertEquals(
        Optional.of(new AuthorizationId("release-" + state.requestId().value())),
        state.notificationIdempotencyKey());
    assertEquals(1, context.notifications.sentCount());
    assertEquals(
        ClearanceOutcome.GREEN,
        state.finalDecision().orElseThrow().combinedOutcomes().get(ClearanceBranch.FINANCE));
  }

  @Test
  void redOutcomeHoldsAndAmberOutcomeRoutesForExceptionApproval() {
    SliceContext redContext = new SliceContext(new BusinessKey("slice-red"));
    KeyHandoverState red = redContext.submit();
    red =
        redContext.complete(
            red, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, redContext.handoverActor());
    red = redContext.completeFinance(red, redContext.financeActor());
    red =
        redContext.complete(
            red, ClearanceBranch.LEGAL, ClearanceOutcome.RED, redContext.legalActor());
    assertEquals(RequestStatus.HOLD, red.status());

    SliceContext amberContext = new SliceContext(new BusinessKey("slice-amber"));
    KeyHandoverState amber = amberContext.submit();
    amber =
        amberContext.complete(
            amber, ClearanceBranch.HANDOVER, ClearanceOutcome.AMBER, amberContext.handoverActor());
    amber = amberContext.completeFinance(amber, amberContext.financeActor());
    amber =
        amberContext.complete(
            amber, ClearanceBranch.LEGAL, ClearanceOutcome.GREEN, amberContext.legalActor());
    assertEquals(RequestStatus.EXCEPTION_APPROVAL_REQUIRED, amber.status());
  }

  @Test
  void duplicateCompletionIsIdempotentButConflictingDuplicateIsRejectedAndAudited() {
    SliceContext context = new SliceContext(new BusinessKey("slice-duplicates"));
    KeyHandoverState state = context.submit();
    BranchCompletion first =
        context.completion(
            state, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, context.handoverActor());

    KeyHandoverState completed = context.service.completeBranch(first);
    KeyHandoverState duplicate = context.service.completeBranch(first);

    assertEquals(completed, duplicate);
    BranchCompletion conflicting =
        new BranchCompletion(
            completed.requestId(),
            ClearanceBranch.HANDOVER,
            ClearanceOutcome.RED,
            List.of(new EvidenceReference("synthetic-conflict-evidence")),
            context.handoverActor(),
            completed.stateVersion(),
            context.correlation(),
            context.causation("conflict"));
    assertThrows(
        ConflictingDuplicateCompletionException.class,
        () -> context.service.completeBranch(conflicting));
    assertTrue(context.audit.hasEvent("ConflictingDuplicateCompletionRejected"));
  }

  @Test
  void stateVersionIsMandatoryAndProtectsOptimisticConcurrency() {
    SliceContext context = new SliceContext(new BusinessKey("slice-version"));
    KeyHandoverState state = context.submit();

    assertThrows(
        OptimisticStateConflictException.class,
        () ->
            context.service.completeBranch(
                new BranchCompletion(
                    state.requestId(),
                    ClearanceBranch.HANDOVER,
                    ClearanceOutcome.GREEN,
                    List.of(new EvidenceReference("synthetic-handover-evidence")),
                    context.handoverActor(),
                    new DomainVersion(99),
                    context.correlation(),
                    context.causation("wrong-version"))));
  }

  @Test
  void restartedServiceContinuesFromDurableStateStore() {
    SliceContext context = new SliceContext(new BusinessKey("slice-restart"));
    KeyHandoverState state = context.submit();

    KeyHandoverApplicationService restarted = context.newService();
    KeyHandoverState continued =
        restarted.completeBranch(
            context.completion(
                state, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, context.handoverActor()));

    assertEquals(new DomainVersion(2), continued.stateVersion());
    assertEquals(
        BranchStatus.COMPLETED,
        context
            .store
            .findById(continued.requestId())
            .orElseThrow()
            .branches()
            .get(ClearanceBranch.HANDOVER)
            .status());
  }

  @Test
  void transientConnectorFailureRetriesThenExhaustsDeterministically() {
    SliceContext recovered = new SliceContext(new BusinessKey("slice-retry-recovered"));
    recovered.inspection.failTransiently(1);
    assertDoesNotThrow(recovered::submit);

    SliceContext exhausted = new SliceContext(new BusinessKey("slice-retry-exhausted"));
    exhausted.inspection.failTransiently(3);
    assertThrows(RetryExhaustedException.class, exhausted::submit);
  }

  @Test
  void notificationFailureIsRecordedAfterAuthorizationStateChange() {
    SliceContext context = new SliceContext(new BusinessKey("slice-notification-failure"));
    KeyHandoverState state = context.submit();
    state =
        context.complete(
            state, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, context.handoverActor());
    state = context.completeFinance(state, context.financeActor());
    context.notifications.failNext();
    KeyHandoverState readyForLegal = state;

    assertThrows(
        TransientConnectorException.class,
        () ->
            context.complete(
                readyForLegal,
                ClearanceBranch.LEGAL,
                ClearanceOutcome.GREEN,
                context.legalActor()));

    KeyHandoverState recovered = context.store.findById(state.requestId()).orElseThrow();
    assertEquals(RequestStatus.NOTIFICATION_FAILED, recovered.status());
    assertTrue(context.audit.hasEvent("NotificationFailed"));
  }

  @Test
  void humanTaskAuthorizationSegregationSlaAndEmergencyReassignmentAreEnforced() {
    SliceContext context = new SliceContext(new BusinessKey("slice-human-task"));
    KeyHandoverState state = context.submit();
    KeyHandoverState submitted = state;

    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            context.service.viewTask(
                submitted.requestId(), ClearanceBranch.HANDOVER, context.unauthorizedActor()));

    state =
        context.service.claimTask(
            state.requestId(),
            ClearanceBranch.HANDOVER,
            context.handoverActor(),
            state.stateVersion(),
            context.correlation(),
            context.causation("claim"));
    assertEquals(
        Optional.of(context.handoverActor().actorId()),
        state.branches().get(ClearanceBranch.HANDOVER).assignedTo());

    context.clock.setNow(START.plus(Duration.ofHours(4)));
    KeyHandoverState sla =
        context.service.evaluateSla(state.requestId(), Duration.ofHours(1), Duration.ofHours(3));
    assertTrue(sla.branches().get(ClearanceBranch.HANDOVER).slaWarningAt().isPresent());
    assertTrue(sla.branches().get(ClearanceBranch.HANDOVER).slaBreachedAt().isPresent());

    KeyHandoverState reassigned =
        context.service.emergencyReassign(
            new EmergencyReassignment(
                sla.requestId(),
                ClearanceBranch.HANDOVER,
                context.teamHeadActor(),
                new ActorId("synthetic-emergency-assignee"),
                "synthetic authorized emergency reassignment",
                START.plus(Duration.ofDays(1)),
                sla.stateVersion(),
                context.correlation(),
                context.causation("reassign")));
    assertEquals(
        Optional.of(new ActorId("synthetic-emergency-assignee")),
        reassigned.branches().get(ClearanceBranch.HANDOVER).assignedTo());

    KeyHandoverState completed =
        context.complete(
            reassigned, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, context.handoverActor());
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            context.complete(
                completed,
                ClearanceBranch.FINANCE,
                ClearanceOutcome.GREEN,
                context.handoverActor()));
  }

  @Test
  void delegationPolicyNeverExpandsAuthority() {
    NonExpandingDelegationPolicy delegationPolicy = new NonExpandingDelegationPolicy();
    Actor delegator =
        actor("synthetic-delegator", EnumSet.allOf(Permission.class), Set.of("scope-a", "scope-b"));
    Actor delegate =
        actor("synthetic-delegate", EnumSet.allOf(Permission.class), Set.of("scope-a"));
    Actor expandedDelegate =
        actor(
            "synthetic-expanded-delegate",
            EnumSet.allOf(Permission.class),
            Set.of("scope-a", "scope-c"));

    assertDoesNotThrow(
        () -> delegationPolicy.verifyDelegationDoesNotIncreaseAuthority(delegator, delegate));
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            delegationPolicy.verifyDelegationDoesNotIncreaseAuthority(delegator, expandedDelegate));
  }

  @Test
  void auditSinkFailureIsNotSwallowedAndLeavesRecoverableState() {
    SliceContext context = new SliceContext(new BusinessKey("slice-audit-failure"));
    context.audit.failNext();

    assertThrows(IllegalStateException.class, context::submit);

    KeyHandoverRequestId requestId = new KeyHandoverRequestId("khr-" + context.businessKey.value());
    assertNotNull(context.store.findById(requestId).orElseThrow());
  }

  private static final class SliceContext {
    private final BusinessKey businessKey;
    private final PropertyReference propertyReference = new PropertyReference("synthetic-property");
    private final com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.OwnerReference
        ownerReference =
            new com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.OwnerReference(
                "synthetic-owner");
    private final ControllableClock clock = new ControllableClock(START);
    private final InMemoryDurableKeyHandoverStateStore store =
        new InMemoryDurableKeyHandoverStateStore();
    private final SyntheticInspectionConnector inspection = new SyntheticInspectionConnector();
    private final SyntheticFinanceConnector finance = new SyntheticFinanceConnector();
    private final SyntheticLegalConnector legal = new SyntheticLegalConnector();
    private final RecordingNotificationConnector notifications =
        new RecordingNotificationConnector();
    private final RecordingAuditSink audit = new RecordingAuditSink();
    private final SlicePolicies policies = policies();
    private final KeyHandoverApplicationService service;

    private SliceContext(BusinessKey businessKey) {
      this.businessKey = businessKey;
      finance.setClearance(
          new FinanceClearance(
              BigDecimal.ZERO,
              ClearanceOutcome.GREEN,
              new EvidenceReference("synthetic-finance-green-evidence")));
      service = newService();
    }

    private KeyHandoverApplicationService newService() {
      return new KeyHandoverApplicationService(
          new SyntheticPropertyConnector(propertyReference),
          new SyntheticOwnerIdentityConnector(propertyReference, ownerReference),
          inspection,
          finance,
          legal,
          notifications,
          new SyntheticEvidenceStore(),
          new DeterministicDecisionService(policies.decisionPolicyVersion()),
          audit,
          clock,
          store,
          new PermissionAuthorizationService(),
          policies);
    }

    private KeyHandoverState submit() {
      return service.submit(
          new KeyHandoverSubmission(
              businessKey,
              propertyReference,
              ownerReference,
              submitter(),
              correlation(),
              causation("submit")));
    }

    private KeyHandoverState complete(
        KeyHandoverState state, ClearanceBranch branch, ClearanceOutcome outcome, Actor actor) {
      return service.completeBranch(completion(state, branch, outcome, actor));
    }

    private KeyHandoverState completeFinance(KeyHandoverState state, Actor actor) {
      return service.completeFinanceBranch(
          state.requestId(), actor, state.stateVersion(), correlation(), causation("finance"));
    }

    private BranchCompletion completion(
        KeyHandoverState state, ClearanceBranch branch, ClearanceOutcome outcome, Actor actor) {
      EvidenceReference evidence =
          branch == ClearanceBranch.LEGAL
              ? legal.legalEvidence(propertyReference, ownerReference)
              : new EvidenceReference(
                  "synthetic-" + branch.name().toLowerCase() + "-" + outcome.name().toLowerCase());
      return new BranchCompletion(
          state.requestId(),
          branch,
          outcome,
          List.of(evidence),
          actor,
          state.stateVersion(),
          correlation(),
          causation(branch.name().toLowerCase()));
    }

    private Actor submitter() {
      return actor("synthetic-submitter", EnumSet.allOf(Permission.class), Set.of("submit-scope"));
    }

    private Actor handoverActor() {
      return actor(
          "synthetic-handover-actor", EnumSet.allOf(Permission.class), Set.of("handover-scope"));
    }

    private Actor financeActor() {
      return actor(
          "synthetic-finance-actor", EnumSet.allOf(Permission.class), Set.of("finance-scope"));
    }

    private Actor legalActor() {
      return actor("synthetic-legal-actor", EnumSet.allOf(Permission.class), Set.of("legal-scope"));
    }

    private Actor teamHeadActor() {
      return actor(
          "synthetic-team-head", EnumSet.allOf(Permission.class), Set.of("handover-scope"));
    }

    private Actor unauthorizedActor() {
      return actor("synthetic-unauthorized", EnumSet.noneOf(Permission.class), Set.of());
    }

    private CorrelationId correlation() {
      return new CorrelationId("corr-" + businessKey.value());
    }

    private CausationId causation(String step) {
      return new CausationId("cause-" + businessKey.value() + "-" + step);
    }
  }

  private static SlicePolicies policies() {
    Map<ClearanceBranch, HumanTaskPolicy> taskPolicies = new EnumMap<>(ClearanceBranch.class);
    taskPolicies.put(
        ClearanceBranch.HANDOVER,
        taskPolicy(
            "synthetic-handover-eligibility", "synthetic-handover-policy", "handover-scope"));
    taskPolicies.put(
        ClearanceBranch.FINANCE,
        taskPolicy("synthetic-finance-eligibility", "synthetic-finance-policy", "finance-scope"));
    taskPolicies.put(
        ClearanceBranch.LEGAL,
        taskPolicy("synthetic-legal-eligibility", "synthetic-legal-policy", "legal-scope"));
    return new SlicePolicies(
        taskPolicies, new PolicyRef("synthetic-key-handover-decision-policy-v1"), 2, Duration.ZERO);
  }

  private static HumanTaskPolicy taskPolicy(String eligibility, String prefix, String scope) {
    return new HumanTaskPolicy(
        new TeamOrRoleRef(eligibility),
        AssignmentMode.AUTOMATIC,
        new PolicyRef(prefix + "-assignment-v1"),
        new PolicyRef(prefix + "-sla-v1"),
        new PolicyRef(prefix + "-escalation-v1"),
        1,
        Set.of(scope));
  }

  private static Actor actor(String actorId, Set<Permission> permissions, Set<String> scopes) {
    return new Actor(new ActorId(actorId), permissions, scopes);
  }
}
