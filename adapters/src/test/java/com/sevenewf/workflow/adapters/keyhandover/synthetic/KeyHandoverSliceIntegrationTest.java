package com.sevenewf.workflow.adapters.keyhandover.synthetic;

import static org.junit.jupiter.api.Assertions.*;

import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.*;
import com.sevenewf.workflow.domain.common.*;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverApplicationService;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.*;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.KeyHandoverStateStore;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverState;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

final class KeyHandoverSliceIntegrationTest {
  private static final Instant START = Instant.parse("2026-06-19T08:00:00Z");

  @Test
  void inspectionBarrierPreventsClearanceAndAuthorizationUntilExplicitResume() {
    SliceContext context = new SliceContext("inspection-barrier");
    context.inspection.setInspectionStatus(new InspectionStatus(false, Optional.empty()));
    KeyHandoverState waiting = context.submit();
    assertEquals(RequestStatus.WAITING_FOR_INSPECTION, waiting.status());
    assertTrue(waiting.branches().isEmpty());
    assertThrows(
        ValidationFailedException.class,
        () ->
            context.service.claimTask(
                waiting.requestId(),
                ClearanceBranch.FINANCE,
                context.financeActor(),
                waiting.stateVersion(),
                context.correlation(),
                context.causation("claim")));
    assertThrows(
        ValidationFailedException.class,
        () ->
            context.service.completeFinanceBranch(
                waiting.requestId(),
                context.financeActor(),
                waiting.stateVersion(),
                context.correlation(),
                context.causation("finance")));
    assertEquals(0, context.finance.calls());

    KeyHandoverState resumed =
        context.service.resumeAfterInspection(context.inspectionAvailable(waiting));
    assertEquals(RequestStatus.CLEARANCE_IN_PROGRESS, resumed.status());
    assertEquals(3, resumed.branches().size());
    assertEquals(
        resumed, context.service.resumeAfterInspection(context.inspectionAvailable(resumed)));
    assertTrue(context.audit.hasEvent("InspectionAvailable"));
  }

  @Test
  void submissionRequiresDistinctSubmitPermission() {
    SliceContext context = new SliceContext("submit-permission");
    Actor noSubmit =
        context.actor(
            "no-submit",
            EnumSet.of(Permission.CLAIM_TASK),
            Set.of("handover-scope"),
            context.handoverRole());
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            context.service.submit(
                new KeyHandoverSubmission(
                    context.businessKey,
                    context.property,
                    context.owner,
                    noSubmit,
                    context.correlation(),
                    context.causation("submit"))));
  }

  @Test
  void financeAuthorizationAndVersionChecksRunBeforeConnector() {
    SliceContext context = new SliceContext("finance-ordering");
    KeyHandoverState state = context.submit();
    Actor ineligible =
        context.actor(
            "ineligible",
            EnumSet.allOf(Permission.class),
            Set.of("finance-scope"),
            context.handoverRole());
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            context.service.completeFinanceBranch(
                state.requestId(),
                ineligible,
                state.stateVersion(),
                context.correlation(),
                context.causation("ineligible")));
    assertEquals(0, context.finance.calls());
    assertThrows(
        OptimisticStateConflictException.class,
        () ->
            context.service.completeFinanceBranch(
                state.requestId(),
                context.financeActor(),
                new DomainVersion(99),
                context.correlation(),
                context.causation("stale")));
    assertEquals(0, context.finance.calls());
  }

  @Test
  void policiesEnforceEligibilityAuthorityAssignmentAndSegregation() {
    SliceContext context = new SliceContext("task-policies");
    KeyHandoverState state = context.submit();
    Actor noScope =
        context.actor(
            "no-scope", EnumSet.allOf(Permission.class), Set.of(), context.handoverRole());
    KeyHandoverState submitted = state;
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            context.service.claimTask(
                submitted.requestId(),
                ClearanceBranch.HANDOVER,
                noScope,
                submitted.stateVersion(),
                context.correlation(),
                context.causation("scope")));
    state = context.claim(state, ClearanceBranch.HANDOVER, context.handoverActor());
    Actor otherEligible =
        context.actor(
            "other-handover",
            EnumSet.allOf(Permission.class),
            Set.of("handover-scope"),
            context.handoverRole());
    KeyHandoverState claimed = state;
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            context.service.completeBranch(
                context.completion(
                    claimed, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, otherEligible)));
    state =
        context.complete(
            state, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, context.handoverActor());
    KeyHandoverState completed = state;
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            context.service.claimTask(
                completed.requestId(),
                ClearanceBranch.FINANCE,
                context.handoverActor(),
                completed.stateVersion(),
                context.correlation(),
                context.causation("sod")));
  }

  @Test
  void normalAndEmergencyReassignmentEnforcePolicyAndAuditMetadata() {
    SliceContext context = new SliceContext("reassignment");
    KeyHandoverState state = context.submit();
    KeyHandoverState submitted = state;
    Actor noReassign =
        context.actor(
            "no-reassign",
            EnumSet.of(Permission.CLAIM_TASK),
            Set.of("handover-scope"),
            context.handoverRole());
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            context.service.reassignTask(
                new TaskReassignment(
                    submitted.requestId(),
                    ClearanceBranch.HANDOVER,
                    noReassign,
                    context.handoverActor(),
                    submitted.stateVersion(),
                    context.correlation(),
                    context.causation("normal"))));
    state = context.claim(state, ClearanceBranch.HANDOVER, context.handoverActor());
    Actor replacement =
        context.actor(
            "replacement",
            EnumSet.allOf(Permission.class),
            Set.of("handover-scope"),
            context.handoverRole());
    state =
        context.service.reassignTask(
            new TaskReassignment(
                state.requestId(),
                ClearanceBranch.HANDOVER,
                context.reassigner(),
                replacement,
                state.stateVersion(),
                context.correlation(),
                context.causation("normal")));
    assertEquals(
        Optional.of(replacement.actorId()),
        state.branches().get(ClearanceBranch.HANDOVER).assignedTo());
    KeyHandoverState beforeEmergency = state;
    assertThrows(
        ValidationFailedException.class,
        () ->
            context.service.emergencyReassign(
                new EmergencyReassignment(
                    beforeEmergency.requestId(),
                    ClearanceBranch.HANDOVER,
                    context.teamHead(),
                    replacement,
                    "synthetic reason",
                    START,
                    beforeEmergency.stateVersion(),
                    context.correlation(),
                    context.causation("expired"))));
    state =
        context.service.emergencyReassign(
            new EmergencyReassignment(
                state.requestId(),
                ClearanceBranch.HANDOVER,
                context.teamHead(),
                replacement,
                "synthetic reason",
                START.plusSeconds(60),
                state.stateVersion(),
                context.correlation(),
                context.causation("emergency")));
    AuditRecord audit =
        context.audit.records().stream()
            .filter(record -> record.eventType().equals("EmergencyTaskReassigned"))
            .findFirst()
            .orElseThrow();
    assertEquals("synthetic reason", audit.metadata().get("reason"));
    assertEquals("replacement", audit.metadata().get("newAssignee"));
    assertNotNull(audit.metadata().get("expiry"));
  }

  @Test
  void slaAuditsOnlyNewThresholdCrossingsAndNoOpDoesNotVersionState() {
    SliceContext context = new SliceContext("sla");
    KeyHandoverState state = context.submit();
    context.clock.setNow(START.plusSeconds(30));
    KeyHandoverState noChange =
        context.service.evaluateSla(
            state.requestId(),
            Duration.ofHours(1),
            Duration.ofHours(2),
            context.submitter(),
            context.correlation(),
            context.causation("noop"));
    assertEquals(state.stateVersion(), noChange.stateVersion());
    context.clock.setNow(START.plus(Duration.ofHours(3)));
    KeyHandoverState breached =
        context.service.evaluateSla(
            state.requestId(),
            Duration.ofHours(1),
            Duration.ofHours(2),
            context.submitter(),
            context.correlation(),
            context.causation("breach"));
    assertTrue(context.audit.hasEvent("SlaWarningRecorded"));
    assertTrue(context.audit.hasEvent("SlaBreachRecorded"));
    assertEquals(
        breached.stateVersion(),
        context
            .service
            .evaluateSla(
                breached.requestId(),
                Duration.ofHours(1),
                Duration.ofHours(2),
                context.submitter(),
                context.correlation(),
                context.causation("again"))
            .stateVersion());
  }

  @Test
  void stateAndPendingAuditAreCommittedTogetherAndDeliveredAfterReconstruction() throws Exception {
    Path snapshot = Files.createTempFile("key-handover-audit", ".snapshot");
    SliceContext context =
        new SliceContext("pending-audit", new TestOnlyPathBackedKeyHandoverStateStore(snapshot));
    context.audit.failNext();
    assertThrows(IllegalStateException.class, context::submit);
    assertTrue(context.store.findByBusinessKey(context.businessKey).isPresent());
    assertFalse(context.store.pendingAudits().isEmpty());
    SliceContext restarted = context.restart();
    restarted.service.deliverPendingAudits();
    assertTrue(restarted.store.pendingAudits().isEmpty());
    assertTrue(restarted.audit.hasEvent("KeyHandoverSubmitted"));
    assertTrue(Files.size(snapshot) > 0);
  }

  @Test
  void testOnlyPathBackedStoreSurvivesOriginalStoreAndServiceDestruction() throws Exception {
    Path location = Files.createTempFile("key-handover-store", ".snapshot");
    SliceContext original =
        new SliceContext("durable", new TestOnlyPathBackedKeyHandoverStateStore(location));
    KeyHandoverState state = original.submit();
    KeyHandoverState reconstructed =
        new SliceContext("durable", new TestOnlyPathBackedKeyHandoverStateStore(location))
            .store
            .findById(state.requestId())
            .orElseThrow();
    assertEquals(state, reconstructed);
  }

  @Test
  void retryUsesConfiguredBackoffAndNeverRetriesAuthorizationFailures() {
    SliceContext context = new SliceContext("retry");
    context.inspection.failTransiently(1);
    context.submit();
    assertEquals(List.of(Duration.ofSeconds(5)), context.scheduler.requestedBackoffs());
    SliceContext unauthorized = new SliceContext("retry-auth");
    KeyHandoverState state = unauthorized.submit();
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            unauthorized.service.completeFinanceBranch(
                state.requestId(),
                unauthorized.unauthorized(),
                state.stateVersion(),
                unauthorized.correlation(),
                unauthorized.causation("denied")));
    assertTrue(unauthorized.scheduler.requestedBackoffs().isEmpty());
  }

  @Test
  void notificationRecoveryIsDurableAndIdempotent() {
    SliceContext context = new SliceContext("notification");
    KeyHandoverState state = context.submit();
    state =
        context.complete(
            state, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, context.handoverActor());
    state = context.claim(state, ClearanceBranch.FINANCE, context.financeActor());
    state = context.completeFinance(state);
    state = context.claim(state, ClearanceBranch.LEGAL, context.legalActor());
    context.notifications.failNext();
    KeyHandoverState beforeLegal = state;
    assertThrows(TransientConnectorException.class, () -> context.completeLegal(beforeLegal));
    KeyHandoverState failed = context.store.findById(state.requestId()).orElseThrow();
    assertEquals(RequestStatus.NOTIFICATION_FAILED, failed.status());
    assertTrue(failed.notificationState().orElseThrow().idempotencyKey() instanceof IdempotencyKey);
    SliceContext restarted = context.restart();
    KeyHandoverState delivered =
        restarted.service.retryNotification(
            failed.requestId(),
            restarted.retryActor(),
            failed.stateVersion(),
            restarted.correlation(),
            restarted.causation("retry-notification"));
    assertEquals(
        NotificationDeliveryStatus.DELIVERED, delivered.notificationState().orElseThrow().status());
    assertEquals(1, restarted.notifications.sentCount());
    assertTrue(restarted.audit.hasEvent("NotificationRetryRequested"));
  }

  @Test
  void notificationRetryExhaustionIsAudited() {
    SliceContext context = new SliceContext("notification-exhausted");
    KeyHandoverState state = context.submit();
    state =
        context.complete(
            state, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, context.handoverActor());
    state = context.claim(state, ClearanceBranch.FINANCE, context.financeActor());
    state = context.completeFinance(state);
    state = context.claim(state, ClearanceBranch.LEGAL, context.legalActor());
    context.notifications.failNext();
    KeyHandoverState beforeLegal = state;
    assertThrows(TransientConnectorException.class, () -> context.completeLegal(beforeLegal));
    KeyHandoverState failed = context.store.findById(state.requestId()).orElseThrow();
    context.notifications.failNext();
    assertThrows(
        TransientConnectorException.class,
        () ->
            context.service.retryNotification(
                failed.requestId(),
                context.retryActor(),
                failed.stateVersion(),
                context.correlation(),
                context.causation("retry-failure")));
    KeyHandoverState exhausted = context.store.findById(state.requestId()).orElseThrow();
    assertThrows(
        RetryExhaustedException.class,
        () ->
            context.service.retryNotification(
                exhausted.requestId(),
                context.retryActor(),
                exhausted.stateVersion(),
                context.correlation(),
                context.causation("retry-exhausted")));
    assertTrue(context.audit.hasEvent("NotificationRetryExhausted"));
  }

  @Test
  void decisionUsesCommandCorrelationAndCausationAndLegalEvidenceStorePorts() {
    SliceContext context = new SliceContext("traceability");
    KeyHandoverState state = context.submit();
    state =
        context.complete(
            state, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, context.handoverActor());
    state = context.claim(state, ClearanceBranch.FINANCE, context.financeActor());
    state = context.completeFinance(state);
    state = context.claim(state, ClearanceBranch.LEGAL, context.legalActor());
    CorrelationId correlation = new CorrelationId("corr-command");
    CausationId causation = new CausationId("cause-command");
    KeyHandoverState decided =
        context.service.completeLegalBranch(
            state.requestId(), context.legalActor(), state.stateVersion(), correlation, causation);
    assertEquals(correlation, decided.finalDecision().orElseThrow().correlationId());
    assertEquals(causation, decided.finalDecision().orElseThrow().causationId());
    assertEquals(1, context.legal.calls());
    assertTrue(context.evidence.calls() >= 3);
  }

  private static final class SliceContext {
    final BusinessKey businessKey;
    final PropertyReference property = new PropertyReference("synthetic-property");
    final OwnerReference owner = new OwnerReference("synthetic-owner");
    final ControllableClock clock = new ControllableClock(START);
    final SyntheticInspectionConnector inspection = new SyntheticInspectionConnector();
    final SyntheticFinanceConnector finance = new SyntheticFinanceConnector();
    final SyntheticLegalConnector legal = new SyntheticLegalConnector();
    final SyntheticEvidenceStore evidence = new SyntheticEvidenceStore();
    final RecordingNotificationConnector notifications = new RecordingNotificationConnector();
    final RecordingAuditSink audit = new RecordingAuditSink();
    final RecordingRetryScheduler scheduler = new RecordingRetryScheduler();
    final KeyHandoverStateStore store;
    final SlicePolicies policies = policies();
    final KeyHandoverApplicationService service;

    SliceContext(String key) {
      this(key, new InMemoryKeyHandoverStateStore());
    }

    SliceContext(String key, KeyHandoverStateStore store) {
      businessKey = new BusinessKey(key);
      this.store = store;
      finance.setClearance(
          new FinanceClearance(
              BigDecimal.ZERO, ClearanceOutcome.GREEN, new EvidenceReference("finance-evidence")));
      service = service(store);
    }

    KeyHandoverApplicationService service(KeyHandoverStateStore stateStore) {
      return new KeyHandoverApplicationService(
          new SyntheticPropertyConnector(property),
          new SyntheticOwnerIdentityConnector(property, owner),
          inspection,
          finance,
          legal,
          notifications,
          evidence,
          new DeterministicDecisionService(policies.decisionPolicyVersion()),
          audit,
          clock,
          stateStore,
          new PermissionAuthorizationService(),
          scheduler,
          policies);
    }

    SliceContext restart() {
      return new SliceContext(businessKey.value(), store);
    }

    KeyHandoverState submit() {
      return service.submit(
          new KeyHandoverSubmission(
              businessKey, property, owner, submitter(), correlation(), causation("submit")));
    }

    KeyHandoverState claim(KeyHandoverState state, ClearanceBranch branch, Actor actor) {
      return service.claimTask(
          state.requestId(),
          branch,
          actor,
          state.stateVersion(),
          correlation(),
          causation("claim-" + branch));
    }

    KeyHandoverState complete(
        KeyHandoverState state, ClearanceBranch branch, ClearanceOutcome outcome, Actor actor) {
      if (state.branches().get(branch).assignedTo().isEmpty()) state = claim(state, branch, actor);
      return service.completeBranch(completion(state, branch, outcome, actor));
    }

    KeyHandoverState completeFinance(KeyHandoverState state) {
      return service.completeFinanceBranch(
          state.requestId(),
          financeActor(),
          state.stateVersion(),
          correlation(),
          causation("finance"));
    }

    KeyHandoverState completeLegal(KeyHandoverState state) {
      return service.completeLegalBranch(
          state.requestId(), legalActor(), state.stateVersion(), correlation(), causation("legal"));
    }

    BranchCompletion completion(
        KeyHandoverState state, ClearanceBranch branch, ClearanceOutcome outcome, Actor actor) {
      return new BranchCompletion(
          state.requestId(),
          branch,
          outcome,
          List.of(new EvidenceReference("evidence-" + branch)),
          actor,
          state.stateVersion(),
          correlation(),
          causation("complete-" + branch));
    }

    InspectionAvailable inspectionAvailable(KeyHandoverState state) {
      return new InspectionAvailable(
          state.requestId(),
          state.stateVersion(),
          submitter(),
          correlation(),
          causation("inspection"),
          new EvidenceReference("inspection-evidence"));
    }

    Actor submitter() {
      return actor(
          "submitter",
          EnumSet.allOf(Permission.class),
          Set.of("submit-scope"),
          new TeamOrRoleRef("submit-role"));
    }

    Actor handoverActor() {
      return actor(
          "handover", EnumSet.allOf(Permission.class), Set.of("handover-scope"), handoverRole());
    }

    Actor financeActor() {
      return actor(
          "finance",
          EnumSet.allOf(Permission.class),
          Set.of("finance-scope"),
          new TeamOrRoleRef("finance-role"));
    }

    Actor legalActor() {
      return actor(
          "legal",
          EnumSet.allOf(Permission.class),
          Set.of("legal-scope"),
          new TeamOrRoleRef("legal-role"));
    }

    Actor reassigner() {
      return actor(
          "reassigner",
          EnumSet.of(Permission.REASSIGN_TASK),
          Set.of(),
          new TeamOrRoleRef("operations-role"));
    }

    Actor teamHead() {
      return actor(
          "team-head",
          EnumSet.of(Permission.EMERGENCY_REASSIGN),
          Set.of("handover-scope"),
          handoverRole());
    }

    Actor retryActor() {
      return actor(
          "retry",
          EnumSet.of(Permission.RETRY_NOTIFICATION),
          Set.of(),
          new TeamOrRoleRef("operations-role"));
    }

    Actor unauthorized() {
      return actor(
          "unauthorized", EnumSet.noneOf(Permission.class), Set.of(), new TeamOrRoleRef("none"));
    }

    TeamOrRoleRef handoverRole() {
      return new TeamOrRoleRef("handover-role");
    }

    Actor actor(String id, Set<Permission> permissions, Set<String> scopes, TeamOrRoleRef role) {
      return new Actor(new ActorId(id), permissions, scopes, Set.of(role));
    }

    CorrelationId correlation() {
      return new CorrelationId("corr-" + businessKey.value());
    }

    CausationId causation(String step) {
      return new CausationId("cause-" + businessKey.value() + "-" + step);
    }
  }

  private static SlicePolicies policies() {
    Map<ClearanceBranch, HumanTaskPolicy> policies = new EnumMap<>(ClearanceBranch.class);
    policies.put(ClearanceBranch.HANDOVER, task("handover-role", "handover", "handover-scope"));
    policies.put(ClearanceBranch.FINANCE, task("finance-role", "finance", "finance-scope"));
    policies.put(ClearanceBranch.LEGAL, task("legal-role", "legal", "legal-scope"));
    return new SlicePolicies(policies, new PolicyRef("decision-v1"), 2, Duration.ofSeconds(5));
  }

  private static HumanTaskPolicy task(String role, String prefix, String scope) {
    return new HumanTaskPolicy(
        new TeamOrRoleRef(role),
        AssignmentMode.MANUAL,
        new PolicyRef(prefix + "-assignment"),
        new PolicyRef(prefix + "-sla"),
        new PolicyRef(prefix + "-escalation"),
        1,
        Set.of(scope));
  }
}
