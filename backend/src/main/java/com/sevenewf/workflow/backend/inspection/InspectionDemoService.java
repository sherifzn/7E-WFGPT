package com.sevenewf.workflow.backend.inspection;

import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionResult.FAILED;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionResult.PASSED;

import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.ControllableClock;
import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.inspection.*;
import com.sevenewf.workflow.domain.inspection.InspectionApplicationService.*;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.*;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EvidenceReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverRequestId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PropertyReference;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public final class InspectionDemoService {
  private final InspectionApplicationService appService;
  private final InspectionProcessStore store;
  private final InspectionPendingEventStore eventStore;
  private final ControllableClock clock = new ControllableClock(Instant.now());
  private final Path dataDirectory;

  public InspectionDemoService(Path dataDirectory) {
    this(dataDirectory, null);
  }

  public InspectionDemoService(Path dataDirectory, InspectionProcessStore sharedStore) {
    this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
    if (sharedStore != null) {
      this.store = sharedStore;
    } else {
      this.store =
          new com.sevenewf.workflow.adapters.inspection.synthetic.InspectionProcessAdapters
              .InspectionProcessSnapshotStore(dataDirectory.resolve("inspection-state.bin"));
    }
    this.eventStore =
        (store instanceof InspectionPendingEventStore) ? (InspectionPendingEventStore) store : null;
    this.appService = new InspectionApplicationService(store);
  }

  public record ApiResponse(int status, String body) {}

  public ApiResponse handle(String method, String path, Map<String, String> params) {
    try {
      var segments =
          path.isEmpty() || path.equals("/") ? new String[0] : path.substring(1).split("/");
      if ("OPTIONS".equals(method)) return new ApiResponse(204, "");

      if (segments.length == 0) {
        if ("GET".equals(method)) return listInspections();
        if ("POST".equals(method)) return requestOrCorrelate(params);
        return methodNotAllowed();
      }

      String inspectionId = segments[0];

      if (segments.length == 1) {
        if ("GET".equals(method)) return getInspection(inspectionId);
        return methodNotAllowed();
      }

      if (segments.length >= 2) {
        String action = segments[1];
        return handleAction(method, inspectionId, action, segments, params);
      }
      return notFound();
    } catch (RuntimeException e) {
      return new ApiResponse(400, jsonError(e.getMessage()));
    }
  }

  private ApiResponse handleAction(
      String method,
      String inspectionId,
      String action,
      String[] segments,
      Map<String, String> params) {
    switch (action) {
      case "cancel":
        if ("POST".equals(method)) return cancelInspection(inspectionId, params);
        break;
      case "history":
        if ("GET".equals(method)) return getHistory(inspectionId);
        break;
      case "tasks":
        if (segments.length >= 4 && "claim".equals(segments[3]))
          return claimTask(inspectionId, segments[2], params);
        if (segments.length >= 4 && "claim-remediation".equals(segments[3]))
          return claimRemediation(inspectionId, segments[2], params);
        if (segments.length >= 4 && "claim-reinspection".equals(segments[3]))
          return claimReinspection(inspectionId, segments[2], params);
        if (segments.length >= 4 && "complete-passed".equals(segments[3]))
          return completePassed(inspectionId, segments[2], params);
        if (segments.length >= 4 && "complete-failed".equals(segments[3]))
          return completeFailed(inspectionId, segments[2], params);
        if (segments.length >= 4 && "complete-remediation".equals(segments[3]))
          return completeRemediation(inspectionId, segments[2], params);
        if (segments.length >= 4 && "complete-reinspection".equals(segments[3]))
          return completeReinspection(inspectionId, segments[2], params);
        break;
    }
    return notFound();
  }

  private ApiResponse listInspections() {
    var adapter =
        (com.sevenewf.workflow.adapters.inspection.synthetic.InspectionProcessAdapters
                .InMemoryInspectionProcessStore)
            store;
    List<InspectionProcess> processes = adapter.listAll();
    StringBuilder sb = new StringBuilder("{\"inspections\":[");
    sb.append(
        processes.stream()
            .map(
                p ->
                    "{"
                        + field("id", p.id().value())
                        + ","
                        + field("status", p.status().name())
                        + ","
                        + field("parentRequestId", p.parentRequestId().value())
                        + ","
                        + field("propertyReference", p.propertyReference().value())
                        + "}")
            .collect(Collectors.joining(",")));
    sb.append("]}");
    return new ApiResponse(200, sb.toString());
  }

  private ApiResponse requestOrCorrelate(Map<String, String> params) {
    String propertyReference =
        require(params, "propertyReference", "Property reference is required");
    String inspectionType = params.getOrDefault("inspectionType", "synthetic");
    String parentRequestId = require(params, "parentRequestId", "Parent request ID is required");
    String actorId = params.getOrDefault("actor", "inspection-officer");
    InspectionRequestCommand command =
        new InspectionRequestCommand(
            new KeyHandoverRequestId(parentRequestId),
            new PropertyReference(propertyReference),
            inspectionType,
            metadata(actorId, InspectionRole.INSPECTION_OFFICER, 1, "request"));
    InspectionProcess process = appService.requestOrCorrelate(command);
    return json(201, process);
  }

  private ApiResponse getInspection(String id) {
    InspectionProcess process = store.findById(new InspectionProcessId(id)).orElse(null);
    if (process == null) return notFound();
    return json(200, process);
  }

  private ApiResponse getHistory(String id) {
    InspectionProcess process = store.findById(new InspectionProcessId(id)).orElse(null);
    if (process == null) return notFound();
    return new ApiResponse(200, historyJson(process));
  }

  private ApiResponse claimTask(String inspectionId, String taskId, Map<String, String> params) {
    String actorId = require(params, "actor", "Actor is required");
    InspectionProcess process = store.findById(new InspectionProcessId(inspectionId)).orElse(null);
    if (process == null) return notFound();
    ClaimTaskCommand command =
        new ClaimTaskCommand(
            new InspectionProcessId(inspectionId),
            taskId,
            metadata(
                actorId, InspectionRole.INSPECTION_OFFICER, process.version().value(), "claim"));
    InspectionProcess result = appService.claimInspection(command);
    return json(200, result);
  }

  private ApiResponse claimRemediation(
      String inspectionId, String taskId, Map<String, String> params) {
    String actorId = require(params, "actor", "Actor is required");
    InspectionProcess process = store.findById(new InspectionProcessId(inspectionId)).orElse(null);
    if (process == null) return notFound();
    ClaimTaskCommand command =
        new ClaimTaskCommand(
            new InspectionProcessId(inspectionId),
            taskId,
            metadata(
                actorId, InspectionRole.REMEDIATION_OFFICER, process.version().value(), "claim"));
    InspectionProcess result = appService.claimRemediation(command);
    return json(200, result);
  }

  private ApiResponse claimReinspection(
      String inspectionId, String taskId, Map<String, String> params) {
    String actorId = require(params, "actor", "Actor is required");
    InspectionProcess process = store.findById(new InspectionProcessId(inspectionId)).orElse(null);
    if (process == null) return notFound();
    ClaimTaskCommand command =
        new ClaimTaskCommand(
            new InspectionProcessId(inspectionId),
            taskId,
            metadata(
                actorId, InspectionRole.INSPECTION_OFFICER, process.version().value(), "claim"));
    InspectionProcess result = appService.claimReinspection(command);
    return json(200, result);
  }

  private ApiResponse completePassed(
      String inspectionId, String taskId, Map<String, String> params) {
    return completeInspection(inspectionId, taskId, params, true, false);
  }

  private ApiResponse completeFailed(
      String inspectionId, String taskId, Map<String, String> params) {
    return completeInspection(inspectionId, taskId, params, false, false);
  }

  private ApiResponse completeReinspection(
      String inspectionId, String taskId, Map<String, String> params) {
    return completeInspection(inspectionId, taskId, params, true, true);
  }

  private ApiResponse completeInspection(
      String inspectionId,
      String taskId,
      Map<String, String> params,
      boolean passed,
      boolean reinspection) {
    String actorId = require(params, "actor", "Actor is required");
    String findings = params.getOrDefault("findings", "synthetic findings");
    String evidenceRef = params.getOrDefault("evidenceReference", "evidence-" + UUID.randomUUID());
    InspectionProcess process = store.findById(new InspectionProcessId(inspectionId)).orElse(null);
    if (process == null) return notFound();
    InspectionResult result = passed ? PASSED : FAILED;
    CompleteInspectionCommand command =
        new CompleteInspectionCommand(
            new InspectionProcessId(inspectionId),
            taskId,
            result,
            findings,
            new EvidenceReference(evidenceRef),
            metadata(
                actorId,
                InspectionRole.INSPECTION_OFFICER,
                process.version().value(),
                reinspection ? "reinspection" : "inspection"));
    try {
      InspectionProcess updated;
      if (reinspection) {
        updated = appService.completeReinspection(command);
      } else if (passed) {
        updated = appService.completeInspectionPassed(command);
      } else {
        updated = appService.completeInspectionFailed(command);
      }
      if (passed
          && updated.status() == InspectionProcessStatus.COMPLETED
          && !updated.attempts().isEmpty()) {
        try {
          InspectionAttempt lastAttempt = updated.attempts().get(updated.attempts().size() - 1);
          eventStore.appendPendingResumeEvent(
              new PendingResumeEvent(
                  updated.id(),
                  lastAttempt.number(),
                  lastAttempt.evidenceReference(),
                  false,
                  clock.now()));
        } catch (Exception ignored) {
          // Pending event store might not be available in all setups
        }
      }
      return json(200, updated);
    } catch (InspectionApplicationExceptions.ConflictException e) {
      return new ApiResponse(409, jsonError(e.getMessage()));
    } catch (InspectionApplicationExceptions.AuthorizationDeniedException e) {
      return new ApiResponse(403, jsonError(e.getMessage()));
    }
  }

  private ApiResponse completeRemediation(
      String inspectionId, String taskId, Map<String, String> params) {
    String actorId = require(params, "actor", "Actor is required");
    String resolutionSummary = params.getOrDefault("resolutionSummary", "synthetic resolution");
    String evidenceRef =
        params.getOrDefault("evidenceReference", "remediation-" + UUID.randomUUID());
    InspectionProcess process = store.findById(new InspectionProcessId(inspectionId)).orElse(null);
    if (process == null) return notFound();
    CompleteRemediationCommand command =
        new CompleteRemediationCommand(
            new InspectionProcessId(inspectionId),
            taskId,
            resolutionSummary,
            new EvidenceReference(evidenceRef),
            metadata(
                actorId,
                InspectionRole.REMEDIATION_OFFICER,
                process.version().value(),
                "remediation"));
    try {
      InspectionProcess updated = appService.completeRemediation(command);
      return json(200, updated);
    } catch (InspectionApplicationExceptions.ConflictException e) {
      return new ApiResponse(409, jsonError(e.getMessage()));
    } catch (InspectionApplicationExceptions.AuthorizationDeniedException e) {
      return new ApiResponse(403, jsonError(e.getMessage()));
    }
  }

  private ApiResponse cancelInspection(String inspectionId, Map<String, String> params) {
    String actorId = require(params, "actor", "Actor is required");
    String reason = params.getOrDefault("reason", "synthetic cancellation");
    InspectionProcess process = store.findById(new InspectionProcessId(inspectionId)).orElse(null);
    if (process == null) return notFound();
    CancelInspectionCommand command =
        new CancelInspectionCommand(
            new InspectionProcessId(inspectionId),
            reason,
            metadata(actorId, InspectionRole.PROCESS_OWNER, process.version().value(), "cancel"));
    try {
      InspectionProcess updated = appService.cancel(command);
      return json(200, updated);
    } catch (InspectionApplicationExceptions.ConflictException e) {
      return new ApiResponse(409, jsonError(e.getMessage()));
    } catch (InspectionApplicationExceptions.AuthorizationDeniedException e) {
      return new ApiResponse(403, jsonError(e.getMessage()));
    }
  }

  private InspectionCommandMetadata metadata(
      String actorId, InspectionRole role, int expectedVersion, String causation) {
    return new InspectionCommandMetadata(
        new ActorId(actorId),
        role,
        new DomainVersion(expectedVersion),
        new CorrelationId("correlation-" + causation),
        new CausationId(causation),
        clock.now());
  }

  private static ApiResponse json(int status, InspectionProcess process) {
    return new ApiResponse(status, processToJson(process));
  }

  static String processToJson(InspectionProcess p) {
    StringBuilder sb = new StringBuilder("{");
    sb.append(field("id", p.id().value()));
    sb.append(",").append(field("businessKey", p.businessKey().value()));
    sb.append(",").append(field("parentRequestId", p.parentRequestId().value()));
    sb.append(",").append(field("propertyReference", p.propertyReference().value()));
    sb.append(",").append(field("inspectionType", p.inspectionType()));
    sb.append(",").append(field("status", p.status().name()));
    sb.append(",").append("\"version\":").append(p.version().value());
    if (p.cancellationReason().isPresent())
      sb.append(",").append(field("cancellationReason", p.cancellationReason().get()));
    sb.append(",\"attempts\":[");
    sb.append(
        p.attempts().stream()
            .map(InspectionDemoService::attemptJson)
            .collect(Collectors.joining(",")));
    sb.append("]");
    sb.append(",\"remediationCycles\":[");
    sb.append(
        p.remediationCycles().stream()
            .map(InspectionDemoService::cycleJson)
            .collect(Collectors.joining(",")));
    sb.append("]");
    sb.append(",\"tasks\":[");
    sb.append(
        p.tasks().stream().map(InspectionDemoService::taskJson).collect(Collectors.joining(",")));
    sb.append("]");
    sb.append(",\"correlationId\":\"").append(escape(p.correlationId().value())).append("\"");
    sb.append(",\"causationId\":\"").append(escape(p.causationId().value())).append("\"");
    sb.append(",\"updatedAt\":\"").append(p.updatedAt().toString()).append("\"");
    sb.append("}");
    return sb.toString();
  }

  private static String attemptJson(InspectionAttempt a) {
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"number\":").append(a.number());
    sb.append(",").append(field("result", a.result().name()));
    sb.append(",").append(field("findings", a.findings()));
    sb.append(",").append(field("evidenceReference", a.evidenceReference().value()));
    sb.append(",\"completedAt\":\"").append(a.completedAt().toString()).append("\"");
    a.validUntil()
        .ifPresent(v -> sb.append(",\"validUntil\":\"").append(v.toString()).append("\""));
    sb.append("}");
    return sb.toString();
  }

  private static String cycleJson(RemediationCycle c) {
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"number\":").append(c.number());
    sb.append(",").append(field("status", c.status().name()));
    c.resolutionSummary().ifPresent(s -> sb.append(",").append(field("resolutionSummary", s)));
    c.remediationReference()
        .ifPresent(r -> sb.append(",").append(field("remediationReference", r.value())));
    sb.append("}");
    return sb.toString();
  }

  private static String taskJson(InspectionTask t) {
    StringBuilder sb = new StringBuilder("{");
    sb.append(field("id", t.id()));
    sb.append(",").append(field("type", t.type().name()));
    sb.append(",").append(field("status", t.status().name()));
    sb.append(",").append(field("requiredRole", t.requiredRole().name()));
    t.assignee().ifPresent(a -> sb.append(",").append(field("assignee", a.value())));
    sb.append(",\"createdAt\":\"").append(t.createdAt().toString()).append("\"");
    t.claimedAt().ifPresent(c -> sb.append(",\"claimedAt\":\"").append(c.toString()).append("\""));
    t.completedAt()
        .ifPresent(c -> sb.append(",\"completedAt\":\"").append(c.toString()).append("\""));
    t.outcome().ifPresent(o -> sb.append(",").append(field("outcome", o)));
    sb.append(",").append("\"version\":").append(t.version().value());
    sb.append("}");
    return sb.toString();
  }

  private String historyJson(InspectionProcess p) {
    StringBuilder sb = new StringBuilder("[");
    sb.append(
        p.attempts().stream()
            .map(
                a ->
                    "{"
                        + field("type", "InspectionAttempt")
                        + ",\"number\":"
                        + a.number()
                        + ","
                        + field("result", a.result().name())
                        + ","
                        + field("findings", a.findings())
                        + ",\"completedAt\":\""
                        + a.completedAt()
                        + "\"}")
            .collect(Collectors.joining(",")));
    if (!p.attempts().isEmpty() && !p.remediationCycles().isEmpty()) sb.append(",");
    sb.append(
        p.remediationCycles().stream()
            .map(
                c ->
                    "{"
                        + field("type", "RemediationCycle")
                        + ",\"number\":"
                        + c.number()
                        + ","
                        + field("status", c.status().name())
                        + "}")
            .collect(Collectors.joining(",")));
    sb.append("]");
    return sb.toString();
  }

  private static String field(String name, String value) {
    return "\"" + name + "\":\"" + escape(value) + "\"";
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String jsonError(String message) {
    return "{\"error\":\"" + escape(message) + "\"}";
  }

  private static String require(Map<String, String> params, String key, String message) {
    String value = params.get(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
    return value;
  }

  private static ApiResponse notFound() {
    return new ApiResponse(404, jsonError("Inspection process not found"));
  }

  private static ApiResponse methodNotAllowed() {
    return new ApiResponse(405, jsonError("Method not allowed"));
  }
}
