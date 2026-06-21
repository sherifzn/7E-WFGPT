package com.sevenewf.workflow.adapters.inspection.synthetic;

import static com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.Permission.SUBMIT_REQUEST;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.Validation;
import com.sevenewf.workflow.domain.inspection.InspectionPendingEventStore;
import com.sevenewf.workflow.domain.inspection.InspectionProcess;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessId;
import com.sevenewf.workflow.domain.inspection.InspectionProcessStore;
import com.sevenewf.workflow.domain.inspection.PendingResumeEvent;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverApplicationService;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.Clock;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.KeyHandoverStateStore;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.Actor;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.AuditRecord;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.InspectionAvailable;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverRequestId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class InspectionPrerequisiteSatisfiedHandler {
  private static final String HANDLER_ACTOR_ID = "inspection-resume-handler";

  private final InspectionProcessStore inspectionStore;
  private final InspectionPendingEventStore pendingEventStore;
  private final KeyHandoverStateStore keyHandoverStore;
  private final KeyHandoverApplicationService keyHandoverService;
  private final Clock clock;

  public InspectionPrerequisiteSatisfiedHandler(
      InspectionProcessStore inspectionStore,
      InspectionPendingEventStore pendingEventStore,
      KeyHandoverStateStore keyHandoverStore,
      KeyHandoverApplicationService keyHandoverService,
      Clock clock) {
    this.inspectionStore = Validation.requirePresent(inspectionStore, "inspectionStore");
    this.pendingEventStore = Validation.requirePresent(pendingEventStore, "pendingEventStore");
    this.keyHandoverStore = Validation.requirePresent(keyHandoverStore, "keyHandoverStore");
    this.keyHandoverService = Validation.requirePresent(keyHandoverService, "keyHandoverService");
    this.clock = Validation.requirePresent(clock, "clock");
  }

  public void drainPendingEvents() {
    List<PendingResumeEvent> unhandled =
        pendingEventStore.pendingResumeEvents().stream().filter(event -> !event.handled()).toList();
    for (PendingResumeEvent event : unhandled) {
      processEvent(event);
    }
  }

  private void processEvent(PendingResumeEvent event) {
    InspectionProcess inspection = inspectionStore.findById(event.processId()).orElse(null);
    if (inspection == null) {
      pendingEventStore.markResumeEventHandled(event);
      return;
    }
    KeyHandoverRequestId parentId = inspection.parentRequestId();
    var parentState = keyHandoverStore.findById(parentId).orElse(null);
    if (parentState == null) {
      pendingEventStore.markResumeEventHandled(event);
      return;
    }
    CausationId causationId =
        causation(
            event.processId(), event.passedAttemptNumber(), parentState.stateVersion().value());
    keyHandoverStore.appendPendingAudit(
        new AuditRecord(
            "ParentWorkflowResumeRequested",
            parentId,
            parentState.stateVersion(),
            inspection.correlationId(),
            causationId,
            handlerActor().actorId(),
            clock.now(),
            List.of(event.evidenceReference()),
            metadata(event)));
    try {
      InspectionAvailable command =
          new InspectionAvailable(
              parentId,
              parentState.stateVersion(),
              handlerActor(),
              inspection.correlationId(),
              causationId,
              event.evidenceReference());
      keyHandoverService.resumeAfterInspection(command);
      pendingEventStore.markResumeEventHandled(event);
      keyHandoverStore.appendPendingAudit(
          new AuditRecord(
              "ParentWorkflowResumed",
              parentId,
              parentState.stateVersion(),
              inspection.correlationId(),
              causationId,
              handlerActor().actorId(),
              clock.now(),
              List.of(event.evidenceReference()),
              metadata(event)));
    } catch (Exception exception) {
      keyHandoverStore.appendPendingAudit(
          new AuditRecord(
              "ParentResumeFailed",
              parentId,
              parentState.stateVersion(),
              inspection.correlationId(),
              causationId,
              handlerActor().actorId(),
              clock.now(),
              List.of(event.evidenceReference()),
              Map.of(
                  "failureType",
                  exception.getClass().getSimpleName(),
                  "inspectionProcessId",
                  event.processId().value())));
    }
  }

  static CausationId causation(
      InspectionProcessId processId, int attemptNumber, int parentVersion) {
    return new CausationId(
        "inspection-resume-"
            + processId.value()
            + "-attempt-"
            + attemptNumber
            + "-v"
            + parentVersion);
  }

  static Actor handlerActor() {
    return new Actor(
        new ActorId(HANDLER_ACTOR_ID),
        EnumSet.of(SUBMIT_REQUEST),
        Set.of("inspection-handler"),
        Set.of());
  }

  private static Map<String, String> metadata(PendingResumeEvent event) {
    return Map.of(
        "inspectionProcessId", event.processId().value(),
        "passedAttemptNumber", String.valueOf(event.passedAttemptNumber()));
  }
}
