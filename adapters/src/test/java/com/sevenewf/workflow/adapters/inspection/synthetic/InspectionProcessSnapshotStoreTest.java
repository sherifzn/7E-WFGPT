package com.sevenewf.workflow.adapters.inspection.synthetic;

import static com.sevenewf.workflow.adapters.inspection.synthetic.InspectionProcessAdapters.*;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus.IN_PROGRESS;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus.REQUESTED;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus.WAITING_FOR_REINSPECTION;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus.WAITING_FOR_REMEDIATION;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionRole.INSPECTION_OFFICER;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionRole.REMEDIATION_OFFICER;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTaskStatus.OPEN;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTaskType.INSPECTION;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTaskType.REMEDIATION;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.RemediationStatus.REQUIRED;
import static org.junit.jupiter.api.Assertions.*;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.inspection.InspectionApplicationExceptions.ConflictException;
import com.sevenewf.workflow.domain.inspection.InspectionProcess;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionAttempt;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionBusinessKey;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessId;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionResult;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTask;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTaskStatus;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.RemediationCycle;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.RemediationStatus;
import com.sevenewf.workflow.domain.inspection.PendingResumeEvent;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EvidenceReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverRequestId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PropertyReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class InspectionProcessSnapshotStoreTest {
  private static final Instant NOW = Instant.parse("2026-06-21T10:00:00Z");

  @Test
  void savesAndReloadsCompleteAggregate() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess original = sampleProcess("snapshot-reload");

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(original);

    InspectionProcessSnapshotStore reloaded = new InspectionProcessSnapshotStore(snapshot);
    InspectionProcess restored = reloaded.findById(original.id()).orElseThrow();

    assertRestored(original, restored);
  }

  @Test
  void businessKeyLookupSurvivesRestart() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process = sampleProcess("bk-lookup");

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process);
    InspectionProcessSnapshotStore reloaded = new InspectionProcessSnapshotStore(snapshot);

    InspectionProcess found = reloaded.findByBusinessKey(process.businessKey()).orElseThrow();
    assertEquals(process.id(), found.id());
  }

  @Test
  void processIdLookupSurvivesRestart() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process = sampleProcess("id-lookup");

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process);
    InspectionProcessSnapshotStore reloaded = new InspectionProcessSnapshotStore(snapshot);

    InspectionProcess found = reloaded.findById(process.id()).orElseThrow();
    assertEquals(process.businessKey(), found.businessKey());
  }

  @Test
  void attemptsSurviveRestart() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process = samplePassedProcess("attempts-survive");

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process);
    InspectionProcessSnapshotStore reloaded = new InspectionProcessSnapshotStore(snapshot);

    InspectionProcess restored = reloaded.findById(process.id()).orElseThrow();
    assertEquals(process.attempts().size(), restored.attempts().size());
    InspectionAttempt originalAttempt = process.attempts().get(0);
    InspectionAttempt restoredAttempt = restored.attempts().get(0);
    assertEquals(originalAttempt.number(), restoredAttempt.number());
    assertEquals(originalAttempt.result(), restoredAttempt.result());
    assertEquals(originalAttempt.findings(), restoredAttempt.findings());
    assertEquals(originalAttempt.evidenceReference(), restoredAttempt.evidenceReference());
    assertEquals(originalAttempt.completedAt(), restoredAttempt.completedAt());
    assertEquals(originalAttempt.validUntil(), restoredAttempt.validUntil());
  }

  @Test
  void remediationCyclesSurviveRestart() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process = sampleFailedProcess("remediation-survive");

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process);
    InspectionProcessSnapshotStore reloaded = new InspectionProcessSnapshotStore(snapshot);

    InspectionProcess restored = reloaded.findById(process.id()).orElseThrow();
    assertEquals(process.remediationCycles().size(), restored.remediationCycles().size());
    assertEquals(
        process.remediationCycles().get(0).status(), restored.remediationCycles().get(0).status());
  }

  @Test
  void completedRemediationCyclesSurviveRestart() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process = sampleCompletedRemediationProcess("completed-remediation-survive");

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process);
    InspectionProcessSnapshotStore reloaded = new InspectionProcessSnapshotStore(snapshot);

    InspectionProcess restored = reloaded.findById(process.id()).orElseThrow();
    RemediationCycle original = process.remediationCycles().get(0);
    RemediationCycle reloadedCycle = restored.remediationCycles().get(0);
    assertEquals(RemediationStatus.COMPLETED, reloadedCycle.status());
    assertEquals(original.resolutionSummary(), reloadedCycle.resolutionSummary());
    assertEquals(original.remediationReference(), reloadedCycle.remediationReference());
  }

  @Test
  void tasksAndAssigneesSurviveRestart() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process = sampleProcessWithClaimedTask("tasks-survive");

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process);
    InspectionProcessSnapshotStore reloaded = new InspectionProcessSnapshotStore(snapshot);

    InspectionProcess restored = reloaded.findById(process.id()).orElseThrow();
    assertEquals(process.tasks().size(), restored.tasks().size());

    InspectionTask originalTask = process.tasks().get(0);
    InspectionTask restoredTask = restored.tasks().get(0);
    assertEquals(originalTask.id(), restoredTask.id());
    assertEquals(originalTask.type(), restoredTask.type());
    assertEquals(originalTask.status(), restoredTask.status());
    assertEquals(originalTask.requiredRole(), restoredTask.requiredRole());
    assertEquals(originalTask.assignee(), restoredTask.assignee());
  }

  @Test
  void pendingResumeEventsSurviveRestart() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process = samplePassedProcess("pending-events");
    PendingResumeEvent event =
        new PendingResumeEvent(
            process.id(), 1, new EvidenceReference("evidence-pass"), false, process.updatedAt());

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process);
    store.appendPendingResumeEvent(event);
    InspectionProcessSnapshotStore reloaded = new InspectionProcessSnapshotStore(snapshot);

    List<PendingResumeEvent> events = reloaded.pendingResumeEvents();
    assertEquals(1, events.size());
    PendingResumeEvent restored = events.get(0);
    assertEquals(event.processId(), restored.processId());
    assertEquals(event.passedAttemptNumber(), restored.passedAttemptNumber());
    assertEquals(event.evidenceReference(), restored.evidenceReference());
    assertEquals(event.handled(), restored.handled());
    assertEquals(event.createdAt(), restored.createdAt());
  }

  @Test
  void handledStatusSurvivesRestart() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process = samplePassedProcess("handled-status");
    PendingResumeEvent event =
        new PendingResumeEvent(
            process.id(), 1, new EvidenceReference("evidence-pass"), false, process.updatedAt());

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process);
    store.appendPendingResumeEvent(event);
    store.markResumeEventHandled(event);
    InspectionProcessSnapshotStore reloaded = new InspectionProcessSnapshotStore(snapshot);

    List<PendingResumeEvent> events = reloaded.pendingResumeEvents();
    assertEquals(1, events.size());
    assertTrue(events.get(0).handled());
  }

  @Test
  void aggregateVersionSurvivesRestart() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process = samplePassedProcess("version-survive");

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process);
    InspectionProcessSnapshotStore reloaded = new InspectionProcessSnapshotStore(snapshot);

    InspectionProcess restored = reloaded.findById(process.id()).orElseThrow();
    assertEquals(process.version(), restored.version());
  }

  @Test
  void staleExpectedVersionIsRejected() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process = sampleProcess("stale-version");

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process);

    assertThrows(
        ConflictException.class,
        () ->
            store.commit(
                new InspectionProcess(
                    process.id(),
                    process.businessKey(),
                    process.parentRequestId(),
                    process.propertyReference(),
                    process.inspectionType(),
                    InspectionProcessStatus.CANCELLED,
                    new DomainVersion(3),
                    process.attempts(),
                    process.remediationCycles(),
                    process.tasks(),
                    Optional.of("stale"),
                    process.correlationId(),
                    process.causationId(),
                    process.updatedAt()),
                new DomainVersion(2)));
  }

  @Test
  void commitAdvancesVersionAndPersists() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process = sampleProcess("commit-version");

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process);

    InspectionProcess updated =
        new InspectionProcess(
            process.id(),
            process.businessKey(),
            process.parentRequestId(),
            process.propertyReference(),
            process.inspectionType(),
            IN_PROGRESS,
            new DomainVersion(2),
            process.attempts(),
            process.remediationCycles(),
            process.tasks(),
            Optional.empty(),
            new CorrelationId("correlation-commit"),
            new CausationId("causation-commit"),
            NOW);

    InspectionProcess committed = store.commit(updated, new DomainVersion(1));
    assertEquals(2, committed.version().value());

    InspectionProcessSnapshotStore reloaded = new InspectionProcessSnapshotStore(snapshot);
    InspectionProcess restored = reloaded.findById(process.id()).orElseThrow();
    assertEquals(IN_PROGRESS, restored.status());
    assertEquals(2, restored.version().value());
  }

  @Test
  void atomicReplacementDoesNotLeavePartialSnapshot() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process1 = sampleProcess("atomic-1");

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process1);

    long sizeAfterFirst = Files.size(snapshot);
    assertTrue(sizeAfterFirst > 0);

    InspectionProcess process2 = sampleProcess("atomic-2");
    store.insertIfAbsent(process2);

    InspectionProcessSnapshotStore reloaded = new InspectionProcessSnapshotStore(snapshot);
    assertTrue(reloaded.findById(process1.id()).isPresent());
    assertTrue(reloaded.findById(process2.id()).isPresent());

    long sizeAfterSecond = Files.size(snapshot);
    assertTrue(sizeAfterSecond > sizeAfterFirst);
  }

  @Test
  void unknownProcessReturnsEmpty() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process = sampleProcess("present");

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process);

    assertTrue(store.findById(new InspectionProcessId("nonexistent")).isEmpty());
    assertTrue(
        store
            .findByBusinessKey(
                InspectionBusinessKey.of(
                    new PropertyReference("nonexistent"),
                    "synthetic",
                    new KeyHandoverRequestId("nonexistent")))
            .isEmpty());
  }

  @Test
  void multiplePendingEventsSurviveRestart() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process = samplePassedProcess("multi-events");

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process);

    PendingResumeEvent event1 =
        new PendingResumeEvent(process.id(), 1, new EvidenceReference("evidence-1"), false, NOW);
    PendingResumeEvent event2 =
        new PendingResumeEvent(
            process.id(), 1, new EvidenceReference("evidence-2"), false, NOW.plusSeconds(60));

    store.appendPendingResumeEvent(event1);
    store.appendPendingResumeEvent(event2);
    store.markResumeEventHandled(event1);

    InspectionProcessSnapshotStore reloaded = new InspectionProcessSnapshotStore(snapshot);
    List<PendingResumeEvent> events = reloaded.pendingResumeEvents();
    assertEquals(2, events.size());
    assertTrue(events.stream().anyMatch(PendingResumeEvent::handled));
    assertTrue(events.stream().anyMatch(e -> !e.handled()));
  }

  @Test
  void insertIfAbsentUnderscoresBusinessKeyUniqueness() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess first = sampleProcess("duplicate-bk");
    InspectionProcess second =
        new InspectionProcess(
            new InspectionProcessId("different-id"),
            first.businessKey(),
            first.parentRequestId(),
            first.propertyReference(),
            first.inspectionType(),
            REQUESTED,
            new DomainVersion(1),
            List.of(),
            List.of(),
            List.of(),
            Optional.empty(),
            new CorrelationId("correlation-second"),
            new CausationId("causation-second"),
            NOW);

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    InspectionProcess inserted1 = store.insertIfAbsent(first);
    InspectionProcess inserted2 = store.insertIfAbsent(second);

    assertSame(inserted1, inserted2);
    assertEquals(first.id(), inserted2.id());
  }

  @Test
  void cancelledProcessSurvivesRestart() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcess process = sampleCancelledProcess("cancelled-survive");

    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);
    store.insertIfAbsent(process);
    InspectionProcessSnapshotStore reloaded = new InspectionProcessSnapshotStore(snapshot);

    InspectionProcess restored = reloaded.findById(process.id()).orElseThrow();
    assertEquals(InspectionProcessStatus.CANCELLED, restored.status());
    assertEquals(process.cancellationReason(), restored.cancellationReason());
  }

  @Test
  void emptyStoreOnFreshFile() throws Exception {
    Path snapshot = Files.createTempFile("inspection-snapshot", ".bin");
    InspectionProcessSnapshotStore store = new InspectionProcessSnapshotStore(snapshot);

    assertTrue(store.pendingResumeEvents().isEmpty());
    assertTrue(store.findById(new InspectionProcessId("anything")).isEmpty());
  }

  private static InspectionProcess sampleProcess(String key) {
    InspectionBusinessKey businessKey = businessKey(key);
    InspectionProcessId id = new InspectionProcessId("inspection-" + key);
    InspectionTask task =
        new InspectionTask(
            id.value() + "-inspection-1",
            INSPECTION,
            OPEN,
            INSPECTION_OFFICER,
            Optional.empty(),
            NOW,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new DomainVersion(1),
            new CorrelationId("correlation-" + key),
            new CausationId("causation-" + key));
    return new InspectionProcess(
        id,
        businessKey,
        new KeyHandoverRequestId("handover-" + key),
        new PropertyReference("property-" + key),
        "synthetic",
        REQUESTED,
        new DomainVersion(1),
        List.of(),
        List.of(),
        List.of(task),
        Optional.empty(),
        new CorrelationId("correlation-" + key),
        new CausationId("causation-" + key),
        NOW);
  }

  private static InspectionProcess samplePassedProcess(String key) {
    InspectionProcess requested = sampleProcess(key);
    InspectionAttempt attempt =
        new InspectionAttempt(
            1,
            InspectionResult.PASSED,
            "all clear",
            new EvidenceReference("evidence-" + key),
            NOW,
            Optional.of(NOW.plusSeconds(3600)));
    InspectionTask completedTask =
        new InspectionTask(
            requested.tasks().get(0).id(),
            INSPECTION,
            InspectionTaskStatus.COMPLETED,
            INSPECTION_OFFICER,
            Optional.of(new ActorId("inspector-" + key)),
            requested.tasks().get(0).createdAt(),
            Optional.of(NOW),
            Optional.of(NOW),
            Optional.of("PASSED"),
            new DomainVersion(2),
            new CorrelationId("correlation-" + key + "-pass"),
            new CausationId("causation-" + key + "-pass"));
    return new InspectionProcess(
        requested.id(),
        requested.businessKey(),
        requested.parentRequestId(),
        requested.propertyReference(),
        requested.inspectionType(),
        InspectionProcessStatus.COMPLETED,
        new DomainVersion(3),
        List.of(attempt),
        List.of(),
        List.of(completedTask),
        Optional.empty(),
        new CorrelationId("correlation-" + key + "-pass"),
        new CausationId("causation-" + key + "-pass"),
        NOW);
  }

  private static InspectionProcess sampleFailedProcess(String key) {
    InspectionProcess requested = sampleProcess(key);
    InspectionAttempt attempt =
        new InspectionAttempt(
            1,
            InspectionResult.FAILED,
            "issues found",
            new EvidenceReference("evidence-" + key),
            NOW,
            Optional.empty());
    RemediationCycle cycle = new RemediationCycle(1, REQUIRED, Optional.empty(), Optional.empty());
    InspectionTask completedInspection =
        new InspectionTask(
            requested.tasks().get(0).id(),
            INSPECTION,
            InspectionTaskStatus.COMPLETED,
            INSPECTION_OFFICER,
            Optional.of(new ActorId("inspector-" + key)),
            requested.tasks().get(0).createdAt(),
            Optional.of(NOW),
            Optional.of(NOW),
            Optional.of("FAILED"),
            new DomainVersion(2),
            new CorrelationId("correlation-" + key + "-fail"),
            new CausationId("causation-" + key + "-fail"));
    InspectionTask remediationTask =
        new InspectionTask(
            requested.id().value() + "-remediation-1",
            REMEDIATION,
            OPEN,
            REMEDIATION_OFFICER,
            Optional.empty(),
            NOW,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new DomainVersion(1),
            new CorrelationId("correlation-" + key + "-remediation"),
            new CausationId("causation-" + key + "-remediation"));
    return new InspectionProcess(
        requested.id(),
        requested.businessKey(),
        requested.parentRequestId(),
        requested.propertyReference(),
        requested.inspectionType(),
        WAITING_FOR_REMEDIATION,
        new DomainVersion(4),
        List.of(attempt),
        List.of(cycle),
        List.of(completedInspection, remediationTask),
        Optional.empty(),
        new CorrelationId("correlation-" + key + "-fail"),
        new CausationId("causation-" + key + "-fail"),
        NOW);
  }

  private static InspectionProcess sampleCompletedRemediationProcess(String key) {
    InspectionProcess failed = sampleFailedProcess(key);
    RemediationCycle completedCycle =
        new RemediationCycle(
            1,
            RemediationStatus.COMPLETED,
            Optional.of("all issues resolved"),
            Optional.of(new EvidenceReference("remediation-ref-" + key)));
    InspectionTask completedRemediation =
        new InspectionTask(
            failed.tasks().get(1).id(),
            REMEDIATION,
            InspectionTaskStatus.COMPLETED,
            REMEDIATION_OFFICER,
            Optional.of(new ActorId("remediator-" + key)),
            failed.tasks().get(1).createdAt(),
            Optional.of(NOW),
            Optional.of(NOW),
            Optional.of("REMEDIATION_COMPLETED"),
            new DomainVersion(2),
            new CorrelationId("correlation-" + key + "-remediation-complete"),
            new CausationId("causation-" + key + "-remediation-complete"));
    InspectionTask reinspectionTask =
        new InspectionTask(
            failed.id().value() + "-inspection-2",
            INSPECTION,
            OPEN,
            INSPECTION_OFFICER,
            Optional.empty(),
            NOW,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            new DomainVersion(1),
            new CorrelationId("correlation-" + key + "-reinspection"),
            new CausationId("causation-" + key + "-reinspection"));
    return new InspectionProcess(
        failed.id(),
        failed.businessKey(),
        failed.parentRequestId(),
        failed.propertyReference(),
        failed.inspectionType(),
        WAITING_FOR_REINSPECTION,
        new DomainVersion(5),
        failed.attempts(),
        List.of(completedCycle),
        List.of(failed.tasks().get(0), completedRemediation, reinspectionTask),
        Optional.empty(),
        new CorrelationId("correlation-" + key + "-remediation-complete"),
        new CausationId("causation-" + key + "-remediation-complete"),
        NOW);
  }

  private static InspectionProcess sampleProcessWithClaimedTask(String key) {
    InspectionProcess requested = sampleProcess(key);
    InspectionTask claimedTask =
        new InspectionTask(
            requested.tasks().get(0).id(),
            INSPECTION,
            InspectionTaskStatus.COMPLETED,
            INSPECTION_OFFICER,
            Optional.of(new ActorId("inspector-" + key)),
            requested.tasks().get(0).createdAt(),
            Optional.of(NOW),
            Optional.empty(),
            Optional.empty(),
            new DomainVersion(2),
            new CorrelationId("correlation-" + key + "-claimed"),
            new CausationId("causation-" + key + "-claimed"));
    return new InspectionProcess(
        requested.id(),
        requested.businessKey(),
        requested.parentRequestId(),
        requested.propertyReference(),
        requested.inspectionType(),
        IN_PROGRESS,
        new DomainVersion(2),
        List.of(),
        List.of(),
        List.of(claimedTask),
        Optional.empty(),
        new CorrelationId("correlation-" + key + "-claimed"),
        new CausationId("causation-" + key + "-claimed"),
        NOW);
  }

  private static InspectionProcess sampleCancelledProcess(String key) {
    InspectionProcess requested = sampleProcess(key);
    return new InspectionProcess(
        requested.id(),
        requested.businessKey(),
        requested.parentRequestId(),
        requested.propertyReference(),
        requested.inspectionType(),
        InspectionProcessStatus.CANCELLED,
        new DomainVersion(2),
        List.of(),
        List.of(),
        requested.tasks(),
        Optional.of("no longer needed"),
        new CorrelationId("correlation-" + key + "-cancel"),
        new CausationId("causation-" + key + "-cancel"),
        NOW);
  }

  private static InspectionBusinessKey businessKey(String key) {
    return InspectionBusinessKey.of(
        new PropertyReference("property-" + key),
        "synthetic",
        new KeyHandoverRequestId("handover-" + key));
  }

  static void assertRestored(InspectionProcess original, InspectionProcess restored) {
    assertEquals(original.id(), restored.id());
    assertEquals(original.businessKey(), restored.businessKey());
    assertEquals(original.parentRequestId(), restored.parentRequestId());
    assertEquals(original.propertyReference(), restored.propertyReference());
    assertEquals(original.inspectionType(), restored.inspectionType());
    assertEquals(original.status(), restored.status());
    assertEquals(original.version(), restored.version());
    assertEquals(original.attempts().size(), restored.attempts().size());
    assertEquals(original.remediationCycles().size(), restored.remediationCycles().size());
    assertEquals(original.tasks().size(), restored.tasks().size());
    assertEquals(original.cancellationReason(), restored.cancellationReason());
    assertEquals(original.correlationId(), restored.correlationId());
    assertEquals(original.causationId(), restored.causationId());
    assertEquals(original.updatedAt(), restored.updatedAt());
  }
}
