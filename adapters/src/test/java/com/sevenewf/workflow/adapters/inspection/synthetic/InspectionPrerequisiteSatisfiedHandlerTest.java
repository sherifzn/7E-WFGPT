package com.sevenewf.workflow.adapters.inspection.synthetic;

import static com.sevenewf.workflow.adapters.inspection.synthetic.InspectionProcessAdapters.InspectionProcessSnapshotStore;
import static com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.*;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionResult.PASSED;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTaskStatus.*;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTaskType.*;
import static org.junit.jupiter.api.Assertions.*;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.inspection.InspectionPendingEventStore;
import com.sevenewf.workflow.domain.inspection.InspectionProcess;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionAttempt;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionBusinessKey;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessId;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTask;
import com.sevenewf.workflow.domain.inspection.InspectionProcessStore;
import com.sevenewf.workflow.domain.inspection.PendingResumeEvent;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverApplicationService;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.*;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverState;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

final class InspectionPrerequisiteSatisfiedHandlerTest {
  private static final Instant START = Instant.parse("2026-06-21T10:00:00Z");

  @Test
  void passedInspectionResumesOnlyHandoverBranch() throws Exception {
    Path snapshot = Files.createTempFile("inspection-handler", ".snapshot");
    HandlerContext ctx = new HandlerContext("handover-resume", snapshot);

    KeyHandoverState waiting = ctx.submitToInspection();
    assertEquals(KeyHandoverTypes.RequestStatus.WAITING_FOR_INSPECTION, waiting.status());

    InspectionProcess passed = ctx.createPassedInspection(waiting.requestId());
    ctx.eventStore.appendPendingResumeEvent(pendingEvent(passed, false));

    ctx.handler.drainPendingEvents();

    KeyHandoverState resumed = ctx.khStore.findById(waiting.requestId()).orElseThrow();
    assertEquals(KeyHandoverTypes.RequestStatus.CLEARANCE_IN_PROGRESS, resumed.status());
    assertFalse(resumed.branches().isEmpty());
    assertTrue(resumed.branches().containsKey(KeyHandoverTypes.ClearanceBranch.HANDOVER));
    assertTrue(resumed.branches().containsKey(KeyHandoverTypes.ClearanceBranch.FINANCE));
    assertTrue(resumed.branches().containsKey(KeyHandoverTypes.ClearanceBranch.LEGAL));
    assertEquals(1, ctx.eventStore.pendingResumeEvents().size());
    assertTrue(ctx.eventStore.pendingResumeEvents().get(0).handled());
    assertTrue(
        ctx.audit.records().stream()
            .anyMatch(a -> a.eventType().equals("ParentWorkflowResumeRequested")));
    assertTrue(
        ctx.khStore.pendingAudits().stream()
            .anyMatch(a -> a.eventType().equals("ParentWorkflowResumed")));
  }

  @Test
  void financeRemainsUnchangedAfterResume() throws Exception {
    Path snapshot = Files.createTempFile("inspection-handler", ".snapshot");
    HandlerContext ctx = new HandlerContext("finance-unchanged", snapshot);

    KeyHandoverState waiting = ctx.submitToInspection();
    InspectionProcess passed = ctx.createPassedInspection(waiting.requestId());
    ctx.eventStore.appendPendingResumeEvent(pendingEvent(passed, false));
    ctx.handler.drainPendingEvents();

    KeyHandoverState resumed = ctx.khStore.findById(waiting.requestId()).orElseThrow();
    var financeBranch = resumed.branches().get(KeyHandoverTypes.ClearanceBranch.FINANCE);
    assertEquals(KeyHandoverTypes.BranchStatus.OPEN, financeBranch.status());
    assertTrue(financeBranch.outcome().isEmpty());
  }

  @Test
  void legalRemainsUnchangedAfterResume() throws Exception {
    Path snapshot = Files.createTempFile("inspection-handler", ".snapshot");
    HandlerContext ctx = new HandlerContext("legal-unchanged", snapshot);

    KeyHandoverState waiting = ctx.submitToInspection();
    InspectionProcess passed = ctx.createPassedInspection(waiting.requestId());
    ctx.eventStore.appendPendingResumeEvent(pendingEvent(passed, false));
    ctx.handler.drainPendingEvents();

    KeyHandoverState resumed = ctx.khStore.findById(waiting.requestId()).orElseThrow();
    var legalBranch = resumed.branches().get(KeyHandoverTypes.ClearanceBranch.LEGAL);
    assertEquals(KeyHandoverTypes.BranchStatus.OPEN, legalBranch.status());
    assertTrue(legalBranch.outcome().isEmpty());
  }

  @Test
  void handledEventIsNotProcessedTwice() throws Exception {
    Path snapshot = Files.createTempFile("inspection-handler", ".snapshot");
    HandlerContext ctx = new HandlerContext("idempotent-handled", snapshot);

    KeyHandoverState waiting = ctx.submitToInspection();
    InspectionProcess passed = ctx.createPassedInspection(waiting.requestId());
    ctx.eventStore.appendPendingResumeEvent(pendingEvent(passed, false));
    ctx.handler.drainPendingEvents();
    long auditCountAfterFirst = ctx.audit.records().size();

    ctx.handler.drainPendingEvents();
    assertEquals(auditCountAfterFirst, ctx.audit.records().size());
    assertTrue(ctx.eventStore.pendingResumeEvents().get(0).handled());
  }

  @Test
  void duplicateDeliveryIsIdempotent() throws Exception {
    Path snapshot = Files.createTempFile("inspection-handler", ".snapshot");
    HandlerContext ctx = new HandlerContext("duplicate-delivery", snapshot);

    KeyHandoverState waiting = ctx.submitToInspection();
    InspectionProcess passed = ctx.createPassedInspection(waiting.requestId());
    PendingResumeEvent event = pendingEvent(passed, false);
    ctx.eventStore.appendPendingResumeEvent(event);
    ctx.eventStore.appendPendingResumeEvent(event);

    ctx.handler.drainPendingEvents();
    KeyHandoverState resumed = ctx.khStore.findById(waiting.requestId()).orElseThrow();
    assertEquals(KeyHandoverTypes.RequestStatus.CLEARANCE_IN_PROGRESS, resumed.status());
  }

  @Test
  void restartReloadsAndRetriesPendingEvents() throws Exception {
    Path snapshot = Files.createTempFile("inspection-handler", ".snapshot");
    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);

    HandlerContext ctx = new HandlerContext("restart-retry", store);
    KeyHandoverState waiting = ctx.submitToInspection();
    InspectionProcess passed = ctx.createPassedInspection(waiting.requestId());
    store.appendPendingResumeEvent(
        new PendingResumeEvent(
            passed.id(), 1, passed.attempts().get(0).evidenceReference(), false, START));

    HandlerContext restarted = new HandlerContext("restart-retry", store, ctx.khStore);
    restarted.handler.drainPendingEvents();

    KeyHandoverState resumed = restarted.khStore.findById(waiting.requestId()).orElseThrow();
    assertEquals(KeyHandoverTypes.RequestStatus.CLEARANCE_IN_PROGRESS, resumed.status());
    assertTrue(restarted.eventStore.pendingResumeEvents().get(0).handled());
  }

  @Test
  void correlationAndCausationArePreserved() throws Exception {
    Path snapshot = Files.createTempFile("inspection-handler", ".snapshot");
    HandlerContext ctx = new HandlerContext("corr-cause", snapshot);

    KeyHandoverState waiting = ctx.submitToInspection();
    InspectionProcess passed = ctx.createPassedInspection(waiting.requestId());
    ctx.eventStore.appendPendingResumeEvent(pendingEvent(passed, false));

    ctx.handler.drainPendingEvents();

    var resumedRequested =
        ctx.audit.records().stream()
            .filter(a -> a.eventType().equals("ParentWorkflowResumeRequested"))
            .findFirst()
            .orElseThrow();
    assertEquals(waiting.requestId(), resumedRequested.requestId());
    assertEquals(passed.correlationId(), resumedRequested.correlationId());
  }

  @Test
  void orphanInspectionEventIsMarkedHandled() throws Exception {
    Path snapshot = Files.createTempFile("inspection-handler", ".snapshot");
    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);

    PendingResumeEvent orphan =
        new PendingResumeEvent(
            new InspectionProcessId("nonexistent"),
            1,
            new EvidenceReference("evidence-orphan"),
            false,
            START);
    store.appendPendingResumeEvent(orphan);

    HandlerContext ctx = new HandlerContext("orphan", store);
    ctx.handler.drainPendingEvents();

    assertTrue(ctx.eventStore.pendingResumeEvents().get(0).handled());
  }

  @Test
  void orphanParentEventIsMarkedHandled() throws Exception {
    Path snapshot = Files.createTempFile("inspection-handler", ".snapshot");
    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);

    InspectionProcess passed = createPassedProcess("orphan-parent");
    store.insertIfAbsent(passed);

    PendingResumeEvent event = pendingEvent(passed, false);
    store.appendPendingResumeEvent(event);

    HandlerContext ctx = new HandlerContext("orphan-parent-ctx", store);
    ctx.handler.drainPendingEvents();

    assertTrue(ctx.eventStore.pendingResumeEvents().get(0).handled());
    assertTrue(
        ctx.audit.records().stream()
            .noneMatch(a -> a.eventType().equals("ParentWorkflowResumeRequested")));
  }

  private static final class HandlerContext {
    final KeyHandoverTypes.BusinessKey businessKey;
    final KeyHandoverTypes.PropertyReference property =
        new KeyHandoverTypes.PropertyReference("synthetic-property");
    final KeyHandoverTypes.OwnerReference owner =
        new KeyHandoverTypes.OwnerReference("synthetic-owner");
    final ControllableClock clock = new ControllableClock(START);
    final SyntheticInspectionConnector inspection = new SyntheticInspectionConnector();
    final SyntheticFinanceConnector finance = new SyntheticFinanceConnector();
    final SyntheticLegalConnector legal = new SyntheticLegalConnector();
    final SyntheticEvidenceStore evidence = new SyntheticEvidenceStore();
    final RecordingNotificationConnector notifications = new RecordingNotificationConnector();
    final RecordingAuditSink audit = new RecordingAuditSink();
    final RecordingRetryScheduler scheduler = new RecordingRetryScheduler();
    final KeyHandoverStateStore khStore;
    final SlicePolicies policies;
    final KeyHandoverApplicationService khService;
    final InspectionProcessStore inspectionStore;
    final InspectionPendingEventStore eventStore;
    final InspectionPrerequisiteSatisfiedHandler handler;

    HandlerContext(String key, Path snapshot) {
      this(key, new InspectionProcessSnapshotStore(snapshot), null);
    }

    HandlerContext(String key, InspectionProcessSnapshotStore store) {
      this(key, store, null);
    }

    HandlerContext(
        String key, InspectionProcessSnapshotStore store, KeyHandoverStateStore khStore) {
      businessKey = new KeyHandoverTypes.BusinessKey(key);
      policies = policies();
      this.khStore = khStore != null ? khStore : new InMemoryKeyHandoverStateStore();
      finance.setClearance(
          new FinanceClearance(
              BigDecimal.ZERO,
              KeyHandoverTypes.ClearanceOutcome.GREEN,
              new KeyHandoverTypes.EvidenceReference("finance-evidence")));
      khService =
          new KeyHandoverApplicationService(
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
              this.khStore,
              new PermissionAuthorizationService(),
              scheduler,
              new FixedAutomaticAssignmentService(handoverActor()),
              policies);
      this.inspectionStore = store;
      this.eventStore = store;
      this.handler =
          new InspectionPrerequisiteSatisfiedHandler(
              inspectionStore, eventStore, this.khStore, khService, clock);
    }

    KeyHandoverState submitToInspection() {
      inspection.setInspectionStatus(
          new KeyHandoverTypes.InspectionStatus(false, Optional.empty()));
      return khService.submit(
          new KeyHandoverSubmission(
              businessKey, property, owner, submitter(), correlation(), causation("submit")));
    }

    InspectionProcess createPassedInspection(KeyHandoverRequestId parentId) {
      InspectionProcessId processId = new InspectionProcessId("inspection-" + businessKey.value());
      InspectionAttempt attempt =
          new InspectionAttempt(
              1,
              PASSED,
              "all clear",
              new EvidenceReference("evidence-" + businessKey.value()),
              START,
              Optional.of(START.plusSeconds(3600)));
      InspectionTask task =
          new InspectionTask(
              processId.value() + "-inspection-1",
              INSPECTION,
              COMPLETED,
              InspectionProcess.InspectionRole.INSPECTION_OFFICER,
              Optional.of(new ActorId("inspector")),
              START,
              Optional.of(START),
              Optional.of(START),
              Optional.of("PASSED"),
              new DomainVersion(2),
              correlation(),
              causation("inspection-pass"));
      InspectionProcess process =
          new InspectionProcess(
              processId,
              InspectionBusinessKey.of(property, "synthetic", parentId),
              parentId,
              property,
              "synthetic",
              InspectionProcess.InspectionProcessStatus.COMPLETED,
              new DomainVersion(3),
              List.of(attempt),
              List.of(),
              List.of(task),
              Optional.empty(),
              correlation(),
              causation("inspection-pass"),
              START);
      inspectionStore.insertIfAbsent(process);
      return process;
    }

    Actor submitter() {
      return actor("submitter");
    }

    Actor handoverActor() {
      return actor("handover");
    }

    Actor actor(String id) {
      return new Actor(
          new ActorId(id),
          EnumSet.allOf(KeyHandoverTypes.Permission.class),
          Set.of(id + "-scope"),
          Set.of(new KeyHandoverTypes.TeamOrRoleRef(id + "-role")));
    }

    CorrelationId correlation() {
      return new CorrelationId("corr-" + businessKey.value());
    }

    CausationId causation(String step) {
      return new CausationId("cause-" + businessKey.value() + "-" + step);
    }

    static SlicePolicies policies() {
      Map<KeyHandoverTypes.ClearanceBranch, KeyHandoverTypes.HumanTaskPolicy> policies =
          new EnumMap<>(KeyHandoverTypes.ClearanceBranch.class);
      policies.put(
          KeyHandoverTypes.ClearanceBranch.HANDOVER,
          task("handover-role", "handover", "handover-scope"));
      policies.put(
          KeyHandoverTypes.ClearanceBranch.FINANCE,
          task("finance-role", "finance", "finance-scope"));
      policies.put(
          KeyHandoverTypes.ClearanceBranch.LEGAL, task("legal-role", "legal", "legal-scope"));
      return new SlicePolicies(
          policies, new KeyHandoverTypes.PolicyRef("decision-v1"), 2, Duration.ofSeconds(5));
    }

    static KeyHandoverTypes.HumanTaskPolicy task(String role, String prefix, String scope) {
      return new KeyHandoverTypes.HumanTaskPolicy(
          new KeyHandoverTypes.TeamOrRoleRef(role),
          KeyHandoverTypes.AssignmentMode.MANUAL,
          new KeyHandoverTypes.PolicyRef(prefix + "-assignment"),
          new KeyHandoverTypes.PolicyRef(prefix + "-sla"),
          new KeyHandoverTypes.PolicyRef(prefix + "-escalation"),
          1,
          Set.of(scope));
    }
  }

  private static PendingResumeEvent pendingEvent(InspectionProcess process, boolean handled) {
    return new PendingResumeEvent(
        process.id(), 1, process.attempts().get(0).evidenceReference(), handled, START);
  }

  private static InspectionProcess createPassedProcess(String key) {
    InspectionProcessId processId = new InspectionProcessId("inspection-" + key);
    InspectionBusinessKey businessKey =
        InspectionBusinessKey.of(
            new KeyHandoverTypes.PropertyReference("prop-" + key),
            "synthetic",
            new KeyHandoverRequestId("handover-" + key));
    InspectionAttempt attempt =
        new InspectionAttempt(
            1,
            PASSED,
            "all clear",
            new EvidenceReference("evidence-" + key),
            START,
            Optional.of(START.plusSeconds(3600)));
    InspectionTask task =
        new InspectionTask(
            processId.value() + "-inspection-1",
            INSPECTION,
            COMPLETED,
            InspectionProcess.InspectionRole.INSPECTION_OFFICER,
            Optional.of(new ActorId("inspector")),
            START,
            Optional.of(START),
            Optional.of(START),
            Optional.of("PASSED"),
            new DomainVersion(2),
            new CorrelationId("corr-" + key),
            new CausationId("cause-" + key));
    return new InspectionProcess(
        processId,
        businessKey,
        new KeyHandoverRequestId("handover-" + key),
        new KeyHandoverTypes.PropertyReference("prop-" + key),
        "synthetic",
        InspectionProcess.InspectionProcessStatus.COMPLETED,
        new DomainVersion(3),
        List.of(attempt),
        List.of(),
        List.of(task),
        Optional.empty(),
        new CorrelationId("corr-" + key),
        new CausationId("cause-" + key),
        START);
  }
}
