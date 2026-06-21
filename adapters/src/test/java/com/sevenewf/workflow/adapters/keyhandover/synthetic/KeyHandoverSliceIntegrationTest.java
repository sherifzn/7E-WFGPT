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
  void inspectionBarrierPreventsHandoverButFinanceAndLegalAreOpenImmediately() {
    SliceContext context = new SliceContext("inspection-barrier");
    context.inspection.setInspectionStatus(new InspectionStatus(false, Optional.empty()));
    KeyHandoverState waiting = context.submit();
    assertEquals(RequestStatus.WAITING_FOR_INSPECTION, waiting.status());
    assertEquals(2, waiting.branches().size());
    assertTrue(waiting.branches().containsKey(ClearanceBranch.FINANCE));
    assertTrue(waiting.branches().containsKey(ClearanceBranch.LEGAL));
    assertEquals(BranchStatus.OPEN, waiting.branches().get(ClearanceBranch.FINANCE).status());
    assertEquals(BranchStatus.OPEN, waiting.branches().get(ClearanceBranch.LEGAL).status());

    assertThrows(
        ValidationFailedException.class,
        () ->
            context.service.claimTask(
                waiting.requestId(),
                ClearanceBranch.HANDOVER,
                context.handoverActor(),
                waiting.stateVersion(),
                context.correlation(),
                context.causation("claim")));

    KeyHandoverState financeClaimed =
        context.claim(waiting, ClearanceBranch.FINANCE, context.financeActor());
    assertEquals(
        BranchStatus.CLAIMED, financeClaimed.branches().get(ClearanceBranch.FINANCE).status());

    KeyHandoverState resumed =
        context.service.resumeAfterInspection(context.inspectionAvailable(financeClaimed));
    assertEquals(RequestStatus.CLEARANCE_IN_PROGRESS, resumed.status());
    assertEquals(3, resumed.branches().size());
    assertTrue(resumed.branches().containsKey(ClearanceBranch.HANDOVER));
    assertTrue(resumed.branches().containsKey(ClearanceBranch.FINANCE));
    assertTrue(resumed.branches().containsKey(ClearanceBranch.LEGAL));
    assertEquals(BranchStatus.CLAIMED, resumed.branches().get(ClearanceBranch.FINANCE).status());
    assertEquals(
        resumed, context.service.resumeAfterInspection(context.inspectionAvailable(resumed)));
    assertTrue(context.audit.hasEvent("InspectionAvailable"));
    assertTrue(context.audit.hasEvent("HandoverTaskOpened"));
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
    SliceContext reconstructedContext =
        new SliceContext("durable", new TestOnlyPathBackedKeyHandoverStateStore(location));
    KeyHandoverState reconstructed =
        reconstructedContext.store.findById(state.requestId()).orElseThrow();
    assertEquals(state, reconstructed);
    KeyHandoverState claimed =
        reconstructedContext.claim(
            reconstructed, ClearanceBranch.HANDOVER, reconstructedContext.handoverActor());
    KeyHandoverState continued =
        reconstructedContext.complete(
            claimed,
            ClearanceBranch.HANDOVER,
            ClearanceOutcome.GREEN,
            reconstructedContext.handoverActor());
    assertEquals(
        BranchStatus.COMPLETED, continued.branches().get(ClearanceBranch.HANDOVER).status());
  }

  @Test
  void connectorBackedBranchesCannotBeCompletedThroughGenericHumanCompletion() {
    SliceContext context = new SliceContext("connector-bypass");
    KeyHandoverState state = context.submit();
    state = context.claim(state, ClearanceBranch.FINANCE, context.financeActor());
    KeyHandoverState financeClaimed = state;
    assertThrows(
        ValidationFailedException.class,
        () ->
            context.service.completeBranch(
                context.completion(
                    financeClaimed,
                    ClearanceBranch.FINANCE,
                    ClearanceOutcome.GREEN,
                    context.financeActor())));
    assertEquals(0, context.finance.calls());
    state = context.claim(state, ClearanceBranch.LEGAL, context.legalActor());
    KeyHandoverState legalClaimed = state;
    assertThrows(
        ValidationFailedException.class,
        () ->
            context.service.completeBranch(
                context.completion(
                    legalClaimed,
                    ClearanceBranch.LEGAL,
                    ClearanceOutcome.RED,
                    context.legalActor())));
    assertEquals(0, context.legal.calls());
    KeyHandoverState legal =
        context.service.completeLegalBranch(
            legalClaimed.requestId(),
            context.legalActor(),
            ClearanceOutcome.RED,
            legalClaimed.stateVersion(),
            context.correlation(),
            context.causation("legal"));
    assertEquals(
        ClearanceOutcome.RED, legal.branches().get(ClearanceBranch.LEGAL).outcome().orElseThrow());
    assertEquals(1, context.legal.calls());
  }

  @Test
  void conflictingDuplicateCompletionRemainsRecoverableWhenAuditDeliveryFails() throws Exception {
    Path location = Files.createTempFile("key-handover-conflict", ".snapshot");
    SliceContext context =
        new SliceContext("conflict-audit", new TestOnlyPathBackedKeyHandoverStateStore(location));
    KeyHandoverState state = context.submit();
    state = context.claim(state, ClearanceBranch.HANDOVER, context.handoverActor());
    state =
        context.complete(
            state, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, context.handoverActor());
    context.audit.failNext();
    KeyHandoverState completed = state;
    assertThrows(
        ConflictingDuplicateCompletionException.class,
        () ->
            context.service.completeBranch(
                context.completion(
                    completed,
                    ClearanceBranch.HANDOVER,
                    ClearanceOutcome.RED,
                    context.handoverActor())));
    assertTrue(
        context.store.pendingAudits().stream()
            .anyMatch(audit -> audit.eventType().equals("ConflictingDuplicateCompletionRejected")));
    SliceContext reconstructed =
        new SliceContext("conflict-audit", new TestOnlyPathBackedKeyHandoverStateStore(location));
    reconstructed.service.deliverPendingAudits();
    assertTrue(reconstructed.audit.hasEvent("ConflictingDuplicateCompletionRejected"));
  }

  @Test
  void claimsAreIdempotentForTheAssigneeAndRejectTaskStealing() {
    SliceContext context = new SliceContext("task-stealing");
    KeyHandoverState state = context.submit();
    KeyHandoverState claimed =
        context.claim(state, ClearanceBranch.HANDOVER, context.handoverActor());
    assertEquals(
        claimed,
        context.service.claimTask(
            claimed.requestId(),
            ClearanceBranch.HANDOVER,
            context.handoverActor(),
            claimed.stateVersion(),
            context.correlation(),
            context.causation("repeat-claim")));
    Actor other =
        context.actor(
            "other-handover",
            EnumSet.of(Permission.CLAIM_TASK),
            Set.of("handover-scope"),
            context.handoverRole());
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            context.service.claimTask(
                claimed.requestId(),
                ClearanceBranch.HANDOVER,
                other,
                claimed.stateVersion(),
                context.correlation(),
                context.causation("steal")));
  }

  @Test
  void reassignmentRejectsACompletedBranchActorAsTheNewAssignee() {
    SliceContext context = new SliceContext("reassignment-sod");
    Actor dualDutyActor =
        new Actor(
            new ActorId("dual-duty"),
            EnumSet.allOf(Permission.class),
            Set.of("handover-scope", "finance-scope"),
            Set.of(context.handoverRole(), new TeamOrRoleRef("finance-role")));
    KeyHandoverState state = context.submit();
    state = context.claim(state, ClearanceBranch.HANDOVER, dualDutyActor);
    state =
        context.complete(state, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, dualDutyActor);
    state = context.claim(state, ClearanceBranch.FINANCE, context.financeActor());
    KeyHandoverState financeClaimed = state;
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            context.service.reassignTask(
                new TaskReassignment(
                    financeClaimed.requestId(),
                    ClearanceBranch.FINANCE,
                    context.reassigner(),
                    dualDutyActor,
                    financeClaimed.stateVersion(),
                    context.correlation(),
                    context.causation("normal-sod"))));
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            context.service.emergencyReassign(
                new EmergencyReassignment(
                    financeClaimed.requestId(),
                    ClearanceBranch.FINANCE,
                    context.teamHead(),
                    dualDutyActor,
                    "synthetic reason",
                    START.plusSeconds(60),
                    financeClaimed.stateVersion(),
                    context.correlation(),
                    context.causation("emergency-sod"))));
  }

  @Test
  void automaticAssignmentUsesTheExplicitPortAndCannotBeManuallyOverridden() {
    SlicePolicies automaticPolicies = policiesWithAutomaticHandover();
    SliceContext context =
        new SliceContext(
            "automatic-assignment", new InMemoryKeyHandoverStateStore(), automaticPolicies);
    KeyHandoverState state = context.submit();
    BranchState automatic = state.branches().get(ClearanceBranch.HANDOVER);
    assertEquals(Optional.of(context.handoverActor().actorId()), automatic.assignedTo());
    assertEquals(
        state,
        context.service.claimTask(
            state.requestId(),
            ClearanceBranch.HANDOVER,
            context.handoverActor(),
            state.stateVersion(),
            context.correlation(),
            context.causation("manual-override")));
    Actor otherEligible =
        context.actor(
            "other-handover",
            EnumSet.of(Permission.CLAIM_TASK),
            Set.of("handover-scope"),
            context.handoverRole());
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            context.service.claimTask(
                state.requestId(),
                ClearanceBranch.HANDOVER,
                otherEligible,
                state.stateVersion(),
                context.correlation(),
                context.causation("automatic-steal")));
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            context.service.completeFinanceBranch(
                state.requestId(),
                context.financeActor(),
                state.stateVersion(),
                context.correlation(),
                context.causation("manual-required")));
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
    assertEquals(RequestStatus.AUTHORIZED, failed.status());
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
            state.requestId(),
            context.legalActor(),
            ClearanceOutcome.GREEN,
            state.stateVersion(),
            correlation,
            causation);
    assertEquals(correlation, decided.finalDecision().orElseThrow().correlationId());
    assertEquals(causation, decided.finalDecision().orElseThrow().causationId());
    assertEquals(1, context.legal.calls());
    assertTrue(context.evidence.calls() >= 3);
  }

  @Test
  void redDecisionCreatesOneDurableHoldAndDoesNotAuthorizeOrNotify() throws Exception {
    Path snapshot = Files.createTempFile("key-handover-hold", ".snapshot");
    SliceContext context =
        new SliceContext("hold-red", new TestOnlyPathBackedKeyHandoverStateStore(snapshot));
    KeyHandoverState held = context.completeToHold(Set.of(ClearanceBranch.HANDOVER));

    assertEquals(RequestStatus.HOLD, held.status());
    assertEquals(1, held.holds().size());
    assertEquals(Set.of(ClearanceBranch.HANDOVER), held.holds().getFirst().affectedBranches());
    assertTrue(held.authorization().isEmpty());
    assertTrue(held.notificationState().isEmpty());
    assertTrue(context.audit.hasEvent("HoldCreated"));

    KeyHandoverState reloaded =
        new SliceContext("hold-red", new TestOnlyPathBackedKeyHandoverStateStore(snapshot))
            .store
            .findById(held.requestId())
            .orElseThrow();
    assertEquals(held.holds(), reloaded.holds());
  }

  @Test
  void onlyProcessOwnerCanRemediateAndResumeAffectedRedBranches() {
    SliceContext context = new SliceContext("hold-resume");
    KeyHandoverState held =
        context.completeToHold(Set.of(ClearanceBranch.HANDOVER, ClearanceBranch.LEGAL));
    KeyHandoverState initialHold = held;
    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            context.service.recordHoldRemediation(
                initialHold.requestId(),
                ClearanceBranch.HANDOVER,
                "resolved",
                new EvidenceReference("resolution"),
                context.teamHead(),
                initialHold.stateVersion(),
                context.correlation(),
                context.causation("unauthorized")));
    held = context.remediate(held, ClearanceBranch.HANDOVER);
    KeyHandoverState incomplete = held;
    assertThrows(
        ValidationFailedException.class,
        () ->
            context.service.resumeHold(
                incomplete.requestId(),
                context.processOwner(),
                incomplete.stateVersion(),
                context.correlation(),
                context.causation("resume")));
    held = context.remediate(held, ClearanceBranch.LEGAL);
    KeyHandoverState resumed = context.resumeHold(held);
    assertEquals(RequestStatus.CLEARANCE_IN_PROGRESS, resumed.status());
    assertEquals(BranchStatus.OPEN, resumed.branches().get(ClearanceBranch.HANDOVER).status());
    assertEquals(BranchStatus.OPEN, resumed.branches().get(ClearanceBranch.LEGAL).status());
    assertEquals(
        ClearanceOutcome.GREEN,
        resumed.branches().get(ClearanceBranch.FINANCE).outcome().orElseThrow());
    assertTrue(context.audit.hasEvent("UnauthorizedHoldAction"));
    assertTrue(context.audit.hasEvent("HoldResumed"));
  }

  @Test
  void expiredHoldRequiresExtensionBeforeResumeAndEnforcesPolicyLimit() {
    SliceContext context = new SliceContext("hold-expiry");
    KeyHandoverState held = context.completeToHold(Set.of(ClearanceBranch.HANDOVER));
    held = context.remediate(held, ClearanceBranch.HANDOVER);
    context.clock.setNow(held.holds().getFirst().expiresAt());
    KeyHandoverState expired =
        context.service.evaluateHold(
            held.requestId(),
            context.processOwner(),
            context.correlation(),
            context.causation("expiry"));
    assertThrows(ValidationFailedException.class, () -> context.resumeHold(expired));
    KeyHandoverState extended = context.extendHold(expired, 5);
    KeyHandoverState secondExtension = context.extendHold(extended, 5);
    assertThrows(IllegalArgumentException.class, () -> context.extendHold(secondExtension, 5));
    assertTrue(context.audit.hasEvent("HoldExpired"));
    assertTrue(context.audit.hasEvent("HoldExtended"));
  }

  @Test
  void rejectAndCancelHoldAreTerminalWithoutAuthorization() {
    SliceContext rejectedContext = new SliceContext("hold-rejected");
    KeyHandoverState rejected =
        rejectedContext.rejectHold(
            rejectedContext.completeToHold(Set.of(ClearanceBranch.HANDOVER)));
    assertEquals(RequestStatus.HOLD_REJECTED, rejected.status());
    assertTrue(rejected.authorization().isEmpty());
    assertTrue(rejectedContext.audit.hasEvent("HoldRejected"));

    SliceContext cancelledContext = new SliceContext("hold-cancelled");
    KeyHandoverState cancelled =
        cancelledContext.cancelHold(
            cancelledContext.completeToHold(Set.of(ClearanceBranch.HANDOVER)));
    assertEquals(RequestStatus.CANCELLED, cancelled.status());
    assertTrue(cancelled.notificationState().isEmpty());
    assertTrue(cancelledContext.audit.hasEvent("HoldCancelled"));
  }

  @Test
  void initializesAndPersistsLegacyHoldWithoutDuplicatingItsCycle() throws Exception {
    Path snapshot = Files.createTempFile("key-handover-legacy-hold", ".snapshot");
    SliceContext context =
        new SliceContext("legacy-hold", new TestOnlyPathBackedKeyHandoverStateStore(snapshot));
    KeyHandoverState held = context.completeToHold(Set.of(ClearanceBranch.LEGAL));
    KeyHandoverState legacy = withoutHolds(held);
    context.store.commit(legacy, held.stateVersion(), List.of());

    KeyHandoverState initialized =
        context.service.initializeLegacyHold(
            legacy.requestId(),
            context.processOwner(),
            context.correlation(),
            context.causation("legacy-initialization"));
    assertEquals(RequestStatus.HOLD, initialized.status());
    assertEquals(1, initialized.holds().size());
    assertEquals(Set.of(ClearanceBranch.LEGAL), initialized.holds().getFirst().affectedBranches());
    assertTrue(initialized.authorization().isEmpty());
    assertTrue(initialized.notificationState().isEmpty());
    assertEquals(
        ClearanceOutcome.GREEN,
        initialized.branches().get(ClearanceBranch.HANDOVER).outcome().orElseThrow());
    assertTrue(context.audit.hasEvent("LegacyHoldInitialized"));
    assertEquals(
        initialized,
        context.service.initializeLegacyHold(
            initialized.requestId(),
            context.processOwner(),
            context.correlation(),
            context.causation("legacy-repeat")));

    KeyHandoverState reloaded =
        new TestOnlyPathBackedKeyHandoverStateStore(snapshot)
            .findById(initialized.requestId())
            .orElseThrow();
    assertEquals(initialized.holds(), reloaded.holds());
  }

  private static KeyHandoverState withoutHolds(KeyHandoverState state) {
    return new KeyHandoverState(
        state.requestId(),
        new DomainVersion(state.stateVersion().value() + 1),
        state.businessKey(),
        state.propertyReference(),
        state.ownerReference(),
        RequestStatus.HOLD,
        state.inspectionStatus(),
        state.inspectionChildWorkflowId(),
        state.branches(),
        state.finalDecision(),
        state.exceptionDecision(),
        Optional.empty(),
        Optional.empty(),
        List.of(),
        state.updatedAt());
  }

  @Test
  void financeAndLegalCanBeCompletedWhileWaitingForInspection() {
    SliceContext context = new SliceContext("finance-legal-waiting");
    context.inspection.setInspectionStatus(new InspectionStatus(false, Optional.empty()));
    KeyHandoverState waiting = context.submit();

    KeyHandoverState financeClaimed =
        context.claim(waiting, ClearanceBranch.FINANCE, context.financeActor());
    KeyHandoverState financeCompleted = context.completeFinance(financeClaimed);
    assertEquals(
        BranchStatus.COMPLETED, financeCompleted.branches().get(ClearanceBranch.FINANCE).status());

    KeyHandoverState legalClaimed =
        context.claim(financeCompleted, ClearanceBranch.LEGAL, context.legalActor());
    KeyHandoverState legalCompleted =
        context.service.completeLegalBranch(
            legalClaimed.requestId(),
            context.legalActor(),
            ClearanceOutcome.GREEN,
            legalClaimed.stateVersion(),
            context.correlation(),
            context.causation("legal"));
    assertEquals(
        BranchStatus.COMPLETED, legalCompleted.branches().get(ClearanceBranch.LEGAL).status());
    assertEquals(RequestStatus.WAITING_FOR_INSPECTION, legalCompleted.status());
    assertTrue(legalCompleted.finalDecision().isEmpty());
  }

  @Test
  void inspectionResumePreservesFinanceAndLegalWithoutDuplication() {
    SliceContext context = new SliceContext("inspection-resume-preservation");
    context.inspection.setInspectionStatus(new InspectionStatus(false, Optional.empty()));
    KeyHandoverState waiting = context.submit();

    KeyHandoverState financeClaimed =
        context.claim(waiting, ClearanceBranch.FINANCE, context.financeActor());
    KeyHandoverState financeCompleted = context.completeFinance(financeClaimed);
    Instant financeCompletedAt =
        financeCompleted.branches().get(ClearanceBranch.FINANCE).openedAt();
    ActorId financeAssignee =
        financeCompleted.branches().get(ClearanceBranch.FINANCE).completedBy().orElseThrow();

    KeyHandoverState legalClaimed =
        context.claim(financeCompleted, ClearanceBranch.LEGAL, context.legalActor());
    KeyHandoverState legalCompleted =
        context.service.completeLegalBranch(
            legalClaimed.requestId(),
            context.legalActor(),
            ClearanceOutcome.GREEN,
            legalClaimed.stateVersion(),
            context.correlation(),
            context.causation("legal"));
    ActorId legalAssignee =
        legalCompleted.branches().get(ClearanceBranch.LEGAL).completedBy().orElseThrow();

    KeyHandoverState resumed =
        context.service.resumeAfterInspection(context.inspectionAvailable(legalCompleted));
    assertEquals(RequestStatus.CLEARANCE_IN_PROGRESS, resumed.status());
    assertEquals(3, resumed.branches().size());
    assertTrue(resumed.branches().containsKey(ClearanceBranch.HANDOVER));
    assertEquals(BranchStatus.COMPLETED, resumed.branches().get(ClearanceBranch.FINANCE).status());
    assertEquals(BranchStatus.COMPLETED, resumed.branches().get(ClearanceBranch.LEGAL).status());
    assertEquals(
        financeAssignee,
        resumed.branches().get(ClearanceBranch.FINANCE).completedBy().orElseThrow());
    assertEquals(
        legalAssignee, resumed.branches().get(ClearanceBranch.LEGAL).completedBy().orElseThrow());
    assertEquals(financeCompletedAt, resumed.branches().get(ClearanceBranch.FINANCE).openedAt());
  }

  @Test
  void finalDecisionRequiresAllThreeBranchesAndNeverAuthorizesWithMissingBranch() {
    SliceContext context = new SliceContext("final-decision-safety");
    KeyHandoverState state = context.submit();
    state = context.claim(state, ClearanceBranch.HANDOVER, context.handoverActor());
    state =
        context.complete(
            state, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, context.handoverActor());
    assertEquals(RequestStatus.CLEARANCE_IN_PROGRESS, state.status());
    assertTrue(state.finalDecision().isEmpty());

    state = context.claim(state, ClearanceBranch.FINANCE, context.financeActor());
    state = context.completeFinance(state);
    assertTrue(state.finalDecision().isEmpty());

    state = context.claim(state, ClearanceBranch.LEGAL, context.legalActor());
    state = context.completeLegal(state);
    assertEquals(RequestStatus.AUTHORIZED, state.status());
    assertTrue(state.finalDecision().isPresent());
  }

  @Test
  void endToEndMissingInspectionThenFinanceLegalThenInspectionThenHandover() {
    SliceContext context = new SliceContext("e2e-missing-inspection");
    context.inspection.setInspectionStatus(new InspectionStatus(false, Optional.empty()));
    KeyHandoverState state = context.submit();
    assertEquals(RequestStatus.WAITING_FOR_INSPECTION, state.status());

    state = context.claim(state, ClearanceBranch.FINANCE, context.financeActor());
    state = context.completeFinance(state);
    state = context.claim(state, ClearanceBranch.LEGAL, context.legalActor());
    state = context.completeLegal(state);
    assertTrue(state.finalDecision().isEmpty());

    state = context.service.resumeAfterInspection(context.inspectionAvailable(state));
    assertEquals(RequestStatus.CLEARANCE_IN_PROGRESS, state.status());
    assertTrue(state.branches().containsKey(ClearanceBranch.HANDOVER));

    state = context.claim(state, ClearanceBranch.HANDOVER, context.handoverActor());
    state =
        context.complete(
            state, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, context.handoverActor());
    assertEquals(RequestStatus.AUTHORIZED, state.status());
    assertTrue(state.authorization().isPresent());
  }

  @Test
  void endToEndInspectionFirstThenHandoverOnlyDoesNotAuthorizeUntilFinanceLegalComplete() {
    SliceContext context = new SliceContext("e2e-inspection-first");
    KeyHandoverState state = context.submit();
    assertEquals(RequestStatus.CLEARANCE_IN_PROGRESS, state.status());
    assertEquals(3, state.branches().size());

    state = context.claim(state, ClearanceBranch.HANDOVER, context.handoverActor());
    state =
        context.complete(
            state, ClearanceBranch.HANDOVER, ClearanceOutcome.GREEN, context.handoverActor());
    assertEquals(RequestStatus.CLEARANCE_IN_PROGRESS, state.status());
    assertTrue(state.finalDecision().isEmpty());

    state = context.claim(state, ClearanceBranch.FINANCE, context.financeActor());
    state = context.completeFinance(state);
    assertTrue(state.finalDecision().isEmpty());

    state = context.claim(state, ClearanceBranch.LEGAL, context.legalActor());
    state = context.completeLegal(state);
    assertEquals(RequestStatus.AUTHORIZED, state.status());
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
    final SlicePolicies policies;
    final KeyHandoverApplicationService service;

    SliceContext(String key) {
      this(key, new InMemoryKeyHandoverStateStore(), policies());
    }

    SliceContext(String key, KeyHandoverStateStore store) {
      this(key, store, policies());
    }

    SliceContext(String key, KeyHandoverStateStore store, SlicePolicies policies) {
      businessKey = new BusinessKey(key);
      this.store = store;
      this.policies = policies;
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
          new FixedAutomaticAssignmentService(handoverActor()),
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

    KeyHandoverState completeToHold(Set<ClearanceBranch> redBranches) {
      KeyHandoverState state = submit();
      state = claim(state, ClearanceBranch.HANDOVER, handoverActor());
      state =
          complete(
              state,
              ClearanceBranch.HANDOVER,
              redBranches.contains(ClearanceBranch.HANDOVER)
                  ? ClearanceOutcome.RED
                  : ClearanceOutcome.GREEN,
              handoverActor());
      state = claim(state, ClearanceBranch.FINANCE, financeActor());
      state = completeFinance(state);
      state = claim(state, ClearanceBranch.LEGAL, legalActor());
      return service.completeLegalBranch(
          state.requestId(),
          legalActor(),
          redBranches.contains(ClearanceBranch.LEGAL)
              ? ClearanceOutcome.RED
              : ClearanceOutcome.GREEN,
          state.stateVersion(),
          correlation(),
          causation("legal"));
    }

    KeyHandoverState remediate(KeyHandoverState state, ClearanceBranch branch) {
      return service.recordHoldRemediation(
          state.requestId(),
          branch,
          "Synthetic remediation",
          new EvidenceReference("remediation-" + branch),
          processOwner(),
          state.stateVersion(),
          correlation(),
          causation("remediation-" + branch));
    }

    KeyHandoverState resumeHold(KeyHandoverState state) {
      return service.resumeHold(
          state.requestId(),
          processOwner(),
          state.stateVersion(),
          correlation(),
          causation("resume"));
    }

    KeyHandoverState extendHold(KeyHandoverState state, int businessDays) {
      return service.extendHold(
          state.requestId(),
          new com.sevenewf.workflow.domain.keyhandover.hold.BusinessDays(businessDays),
          processOwner(),
          state.stateVersion(),
          correlation(),
          causation("extend"));
    }

    KeyHandoverState rejectHold(KeyHandoverState state) {
      return service.rejectHold(
          state.requestId(),
          processOwner(),
          state.stateVersion(),
          correlation(),
          causation("reject"));
    }

    KeyHandoverState cancelHold(KeyHandoverState state) {
      return service.cancelHold(
          state.requestId(),
          processOwner(),
          state.stateVersion(),
          correlation(),
          causation("cancel"));
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
          state.requestId(),
          legalActor(),
          ClearanceOutcome.GREEN,
          state.stateVersion(),
          correlation(),
          causation("legal"));
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

    Actor processOwner() {
      return actor(
          "process-owner",
          EnumSet.allOf(Permission.class),
          Set.of(),
          new TeamOrRoleRef("process-owner-role"));
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

  private static SlicePolicies policiesWithAutomaticHandover() {
    Map<ClearanceBranch, HumanTaskPolicy> policies = new EnumMap<>(ClearanceBranch.class);
    policies.put(
        ClearanceBranch.HANDOVER,
        new HumanTaskPolicy(
            new TeamOrRoleRef("handover-role"),
            AssignmentMode.AUTOMATIC,
            new PolicyRef("handover-assignment"),
            new PolicyRef("handover-sla"),
            new PolicyRef("handover-escalation"),
            1,
            Set.of("handover-scope")));
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
