package com.sevenewf.workflow.backend.keyhandover;

import com.sevenewf.workflow.adapters.inspection.synthetic.InspectionProcessAdapters;
import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.PathBackedKeyHandoverStateStore;
import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.inspection.InspectionApplicationService;
import com.sevenewf.workflow.domain.inspection.InspectionProcess;
import com.sevenewf.workflow.domain.inspection.InspectionProcessStore;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverApplicationService;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.AuthorizationDeniedException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.ConflictingDuplicateCompletionException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.ConflictingExceptionDecisionException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.OptimisticStateConflictException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.ValidationFailedException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.*;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverState;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.*;
import com.sevenewf.workflow.domain.keyhandover.hold.BranchRemediation;
import com.sevenewf.workflow.domain.keyhandover.hold.BusinessDays;
import com.sevenewf.workflow.domain.keyhandover.hold.HoldRecord;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Synthetic-only local API facade. It translates requests to the Task 003 application service. */
public final class KeyHandoverDemoService {
  private final PathBackedKeyHandoverStateStore store;
  private final DemoInspectionConnector inspections = new DemoInspectionConnector();
  private final PersistentDemoAuditSink audits;
  private final DemoNotificationConnector notifications = new DemoNotificationConnector();
  private final KeyHandoverApplicationService service;
  private final InspectionApplicationService inspectionService;
  private final InspectionProcessStore inspectionStore;
  private final Path dataDirectory;
  private final boolean localDevelopmentMode;
  private int sequence;

  public KeyHandoverDemoService() {
    this(Path.of("local-data"));
  }

  public KeyHandoverDemoService(Path dataDirectory) {
    this(dataDirectory, true);
  }

  KeyHandoverDemoService(Path dataDirectory, boolean localDevelopmentMode) {
    this.dataDirectory = dataDirectory;
    this.localDevelopmentMode = localDevelopmentMode;
    store = new PathBackedKeyHandoverStateStore(dataDirectory.resolve("key-handover-state.bin"));
    audits = new PersistentDemoAuditSink(dataDirectory.resolve("audit-events.tsv"));
    inspectionStore =
        new InspectionProcessAdapters.InspectionProcessSnapshotStore(
            dataDirectory.resolve("inspection-state.bin"));
    inspectionService = new InspectionApplicationService(inspectionStore);
    SlicePolicies policies = policies();
    service =
        new KeyHandoverApplicationService(
            property -> true,
            (owner, property) -> true,
            inspections,
            new DemoFinanceConnector(),
            new DemoLegalConnector(),
            notifications,
            (type, key) -> new EvidenceReference("synthetic-" + type + "-" + key.value()),
            new DemoDecisionService(policies.decisionPolicyVersion()),
            audits,
            Instant::now,
            store,
            (actor, permission) -> {
              if (!actor.can(permission)) throw new SecurityException("Permission denied");
            },
            (delay, attempt, operation) -> {},
            policy -> actorFor(policy.eligibleTeamOrRole()),
            policies);
    if (store.states().isEmpty()) seed();
    sequence = nextSequence();
  }

  public ApiResponse handle(String method, String path, Map<String, String> parameters) {
    try {
      if ("GET".equals(method) && (path.isBlank() || "/".equals(path))) return ok(listJson());
      if ("POST".equals(method) && (path.isBlank() || "/".equals(path))) return create(parameters);
      String[] parts = path.replaceFirst("^/", "").split("/");
      if (parts.length == 0 || parts[0].isBlank()) return notFound();
      KeyHandoverState state = state(parts[0]);
      if ("GET".equals(method) && parts.length == 1) return ok(requestJson(state));
      if ("GET".equals(method) && parts.length == 2 && "audit".equals(parts[1]))
        return ok(auditJson(state));
      if ("GET".equals(method) && parts.length == 2 && "notification".equals(parts[1]))
        return ok(notificationJson(state));
      if ("GET".equals(method) && parts.length == 2 && "hold".equals(parts[1]))
        return ok(holdJson(state));
      if (!"POST".equals(method)) return notFound();
      return ok(requestJson(action(state, Arrays.copyOfRange(parts, 1, parts.length), parameters)));
    } catch (SecurityException | AuthorizationDeniedException exception) {
      return new ApiResponse(403, "{\"error\":\"" + escape(exception.getMessage()) + "\"}");
    } catch (OptimisticStateConflictException
        | ConflictingDuplicateCompletionException
        | ConflictingExceptionDecisionException exception) {
      return new ApiResponse(409, "{\"error\":\"" + escape(exception.getMessage()) + "\"}");
    } catch (ValidationFailedException exception) {
      if ("Unknown key handover request".equals(exception.getMessage())) return notFound();
      return new ApiResponse(409, "{\"error\":\"" + escape(exception.getMessage()) + "\"}");
    } catch (RuntimeException exception) {
      return new ApiResponse(400, "{\"error\":\"" + escape(exception.getMessage()) + "\"}");
    }
  }

  private ApiResponse create(Map<String, String> parameters) {
    String property = required(parameters, "propertyReference");
    String owner = required(parameters, "ownerReference");
    String number = "KH-" + sequence++;
    inspections.setWaiting(property, true);
    KeyHandoverState state =
        service.submit(
            new KeyHandoverSubmission(
                new BusinessKey(number),
                new PropertyReference(property),
                new OwnerReference(owner),
                actor(parameters),
                correlation(number),
                causation(number, "submit")));
    InspectionProcess inspection =
        inspectionService.requestOrCorrelate(
            new InspectionApplicationService.InspectionRequestCommand(
                state.requestId(),
                new PropertyReference(property),
                "synthetic",
                new com.sevenewf.workflow.domain.inspection.InspectionCommandMetadata(
                    new ActorId(parameters.getOrDefault("actor", "requester")),
                    InspectionProcess.InspectionRole.INSPECTION_OFFICER,
                    new DomainVersion(1),
                    correlation(number),
                    causation(number, "inspection-request"),
                    java.time.Instant.now())));
    return new ApiResponse(201, requestJson(state, inspection));
  }

  private KeyHandoverState action(
      KeyHandoverState state, String[] action, Map<String, String> parameters) {
    String number = state.businessKey().value();
    if (matches(action, "inspection", "resume"))
      return service.resumeAfterInspection(
          new InspectionAvailable(
              state.requestId(),
              state.stateVersion(),
              actor(parameters),
              correlation(number),
              causation(number, "inspection"),
              new EvidenceReference("synthetic-inspection-" + number)));
    if (matches(action, "notification", "retry"))
      return service.retryNotification(
          state.requestId(),
          actor(parameters),
          state.stateVersion(),
          correlation(number),
          causation(number, "retry"));
    if (matches(action, "notification", "fail-next")) {
      if (!localDevelopmentMode)
        throw new ValidationFailedException("Notification failure simulation is unavailable");
      service.recordNotificationFailureSimulation(
          state.requestId(),
          actor(parameters),
          correlation(parameters, number),
          causation(parameters, number, "notification-failure-simulation"));
      notifications.failNext();
      return state;
    }
    if (matches(action, "hold", "remediation"))
      return service.recordHoldRemediation(
          state.requestId(),
          branch(required(parameters, "branch")),
          required(parameters, "summary"),
          new EvidenceReference(required(parameters, "supportingReference")),
          actor(parameters),
          expectedVersion(parameters, state),
          correlation(parameters, number),
          causation(parameters, number, "hold-remediation"));
    if (matches(action, "hold", "extend")) {
      required(parameters, "reason");
      required(parameters, "reviewAt");
      required(parameters, "expiresAt");
      return service.extendHold(
          state.requestId(),
          new BusinessDays(integer(parameters, "extensionBusinessDays")),
          actor(parameters),
          expectedVersion(parameters, state),
          correlation(parameters, number),
          causation(parameters, number, "hold-extend"));
    }
    if (matches(action, "hold", "resume"))
      return service.resumeHold(
          state.requestId(),
          actor(parameters),
          expectedVersion(parameters, state),
          correlation(parameters, number),
          causation(parameters, number, "hold-resume"));
    if (matches(action, "hold", "reject"))
      return service.rejectHold(
          state.requestId(),
          actor(parameters),
          expectedVersion(parameters, state),
          correlation(parameters, number),
          causation(parameters, number, "hold-reject"));
    if (matches(action, "hold", "cancel"))
      return service.cancelHold(
          state.requestId(),
          actor(parameters),
          expectedVersion(parameters, state),
          correlation(parameters, number),
          causation(parameters, number, "hold-cancel"));
    if (matches(action, "hold", "evaluate"))
      return service.evaluateHold(
          state.requestId(),
          actor(parameters),
          correlation(parameters, number),
          causation(parameters, number, "hold-evaluate"));
    if (matches(action, "exception", "approve") || matches(action, "exception", "reject"))
      return service.decideException(
          new ExceptionDecisionCommand(
              state.requestId(),
              actor(parameters),
              matches(action, "exception", "approve")
                  ? ExceptionDecisionType.APPROVE_EXCEPTION
                  : ExceptionDecisionType.REJECT_EXCEPTION,
              required(parameters, "reason"),
              new com.sevenewf.workflow.domain.common.DomainVersion(
                  integer(parameters, "expectedStateVersion")),
              correlation(parameters, number),
              causation(parameters, number, "exception")));
    if (matches(action, "tasks", "finance", "complete"))
      return service.completeFinanceBranch(
          state.requestId(),
          actor(parameters),
          state.stateVersion(),
          correlation(number),
          causation(number, "finance"));
    if (matches(action, "tasks", "legal", "complete"))
      return service.completeLegalBranch(
          state.requestId(),
          actor(parameters),
          ClearanceOutcome.valueOf(parameters.getOrDefault("outcome", "GREEN")),
          state.stateVersion(),
          correlation(number),
          causation(number, "legal"));
    if (matches(action, "tasks", "handover", "complete"))
      return service.completeBranch(
          new BranchCompletion(
              state.requestId(),
              ClearanceBranch.HANDOVER,
              ClearanceOutcome.valueOf(parameters.getOrDefault("outcome", "GREEN")),
              List.of(new EvidenceReference("synthetic-handover-" + number)),
              actor(parameters),
              state.stateVersion(),
              correlation(number),
              causation(number, "handover")));
    if (action.length == 3 && "tasks".equals(action[0]) && "claim".equals(action[2])) {
      ClearanceBranch branch = branch(action[1]);
      return service.claimTask(
          state.requestId(),
          branch,
          actor(parameters),
          state.stateVersion(),
          correlation(number),
          causation(number, "claim"));
    }
    if (action.length == 3 && "tasks".equals(action[0]) && "reassign".equals(action[2])) {
      ClearanceBranch branch = branch(action[1]);
      return service.reassignTask(
          new TaskReassignment(
              state.requestId(),
              branch,
              actor(parameters),
              assignee(parameters, actorForBranch(branch)),
              state.stateVersion(),
              correlation(number),
              causation(number, "reassign")));
    }
    if (action.length == 3 && "tasks".equals(action[0]) && "emergency-reassign".equals(action[2])) {
      ClearanceBranch branch = branch(action[1]);
      return service.emergencyReassign(
          new EmergencyReassignment(
              state.requestId(),
              branch,
              actor(parameters),
              assignee(parameters, actorForBranch(branch)),
              "synthetic local emergency reassignment",
              Instant.now().plus(Duration.ofMinutes(30)),
              state.stateVersion(),
              correlation(number),
              causation(number, "emergency")));
    }
    throw new IllegalArgumentException("Unknown local demo action");
  }

  private void seed() {
    inspections.setWaiting("Demo Property 101", true);
    createSeed("KH-101", "Demo Property 101", "Demo Owner 101");
    createSeed("KH-102", "Demo Property 102", "Demo Owner 102");
    KeyHandoverState progress = state("KH-102");
    service.claimTask(
        progress.requestId(),
        ClearanceBranch.FINANCE,
        financeActor(),
        progress.stateVersion(),
        correlation("KH-102"),
        causation("KH-102", "claim-finance"));
    createSeed("KH-103", "Demo Property 103", "Demo Owner 103");
    completeAllGreen("KH-103");
    createSeed("KH-104", "Demo Property 104", "Demo Owner 104");
    notifications.failNext();
    try {
      completeAllGreen("KH-104");
    } catch (RuntimeException ignored) {
      // The application service persists a recoverable failed-notification state before surfacing
      // it.
    }
  }

  private void createSeed(String number, String property, String owner) {
    KeyHandoverState state =
        service.submit(
            new KeyHandoverSubmission(
                new BusinessKey(number),
                new PropertyReference(property),
                new OwnerReference(owner),
                submitter(),
                correlation(number),
                causation(number, "submit")));
  }

  private void completeAllGreen(String number) {
    KeyHandoverState state = state(number);
    state = claim(state, ClearanceBranch.HANDOVER, handoverActor());
    state =
        service.completeBranch(
            new BranchCompletion(
                state.requestId(),
                ClearanceBranch.HANDOVER,
                ClearanceOutcome.GREEN,
                List.of(new EvidenceReference("synthetic-handover-" + number)),
                handoverActor(),
                state.stateVersion(),
                correlation(number),
                causation(number, "handover")));
    state = claim(state, ClearanceBranch.FINANCE, financeActor());
    state =
        service.completeFinanceBranch(
            state.requestId(),
            financeActor(),
            state.stateVersion(),
            correlation(number),
            causation(number, "finance"));
    state = claim(state, ClearanceBranch.LEGAL, legalActor());
    service.completeLegalBranch(
        state.requestId(),
        legalActor(),
        ClearanceOutcome.GREEN,
        state.stateVersion(),
        correlation(number),
        causation(number, "legal"));
  }

  private KeyHandoverState claim(KeyHandoverState state, ClearanceBranch branch, Actor actor) {
    return service.claimTask(
        state.requestId(),
        branch,
        actor,
        state.stateVersion(),
        correlation(state.businessKey().value()),
        causation(state.businessKey().value(), "claim"));
  }

  private KeyHandoverState state(String number) {
    return store
        .findByBusinessKey(new BusinessKey(number))
        .map(this::initializeLegacyHold)
        .orElseThrow(() -> new ValidationFailedException("Unknown key handover request"));
  }

  private String listJson() {
    return "["
        + store.states().stream()
            .map(this::initializeLegacyHold)
            .map(this::summaryJson)
            .collect(java.util.stream.Collectors.joining(","))
        + "]";
  }

  private KeyHandoverState initializeLegacyHold(KeyHandoverState state) {
    return service.initializeLegacyHold(
        state.requestId(),
        processOwner(),
        correlation(state.businessKey().value()),
        causation(state.businessKey().value(), "legacy-hold-initialization"));
  }

  private String requestJson(KeyHandoverState state) {
    return requestJson(state, null);
  }

  private String requestJson(KeyHandoverState state, InspectionProcess inspection) {
    StringBuilder sb = new StringBuilder("{");
    sb.append(field("requestNumber", state.businessKey().value()));
    sb.append(",").append(field("property", state.propertyReference().value()));
    sb.append(",").append(field("owner", state.ownerReference().value()));
    sb.append(",").append(field("status", label(state.status())));
    sb.append(",\"stateVersion\":").append(state.stateVersion().value());
    sb.append(",")
        .append(
            field(
                "inspection",
                state.inspectionStatus().validInspectionExists() ? "Available" : "Waiting"));
    if (inspection != null)
      sb.append(",").append(field("inspectionProcessId", inspection.id().value()));
    if (inspection != null)
      sb.append(",").append(field("inspectionProcessId", inspection.id().value()));
    sb.append(",")
        .append(
            field(
                "finalDecision",
                state.finalDecision().map(decision -> label(decision.action())).orElse("")));
    sb.append(",")
        .append(
            field(
                "notification",
                state
                    .notificationState()
                    .map(value -> label(value.status()))
                    .orElse("Not started")));
    sb.append(",\"notificationAttempts\":")
        .append(state.notificationState().map(NotificationState::attemptCount).orElse(0));
    sb.append(",")
        .append(
            field(
                "notificationFailure",
                state
                    .notificationState()
                    .flatMap(NotificationState::lastFailureReference)
                    .map(FailureReference::value)
                    .orElse("")));
    sb.append(",")
        .append(
            field(
                "exceptionDecision",
                state.exceptionDecision().map(value -> label(value.decision())).orElse("")));
    sb.append(",")
        .append(
            field(
                "exceptionReason",
                state.exceptionDecision().map(ExceptionDecision::reason).orElse("")));
    sb.append(",")
        .append(
            field(
                "authorizationId",
                state.authorization().map(value -> value.authorizationId().value()).orElse("")));
    sb.append(",").append(field("lastUpdated", state.updatedAt().toString()));
    sb.append(",\"tasks\":").append(tasksJson(state));
    sb.append(",\"audit\":").append(auditJson(state));
    sb.append(",\"hold\":").append(holdJsonOrNull(state));
    sb.append("}");
    return sb.toString();
  }

  private static String notificationJson(KeyHandoverState state) {
    NotificationState notification =
        state
            .notificationState()
            .orElseThrow(
                () -> new ValidationFailedException("No notification status is available"));
    return "{"
        + field("status", label(notification.status()))
        + ",\"attemptCount\":"
        + notification.attemptCount()
        + ","
        + field(
            "failureReason",
            notification.lastFailureReference().map(FailureReference::value).orElse(""))
        + ","
        + field("lastAttemptAt", state.updatedAt().toString())
        + "}";
  }

  private String holdJson(KeyHandoverState state) {
    String hold = holdJsonOrNull(state);
    if ("null".equals(hold)) throw new ValidationFailedException("No active hold exists");
    return hold;
  }

  private static String holdJsonOrNull(KeyHandoverState state) {
    Optional<HoldRecord> hold = state.holds().stream().reduce((first, second) -> second);
    if (hold.isEmpty()) return "null";
    HoldRecord current = hold.orElseThrow();
    String remediations =
        current.remediationByBranch().values().stream()
            .map(KeyHandoverDemoService::remediationJson)
            .collect(java.util.stream.Collectors.joining(","));
    String affected =
        current.affectedBranches().stream()
            .map(ClearanceBranch::name)
            .map(value -> "\"" + escape(value) + "\"")
            .collect(java.util.stream.Collectors.joining(","));
    return "{"
        + field("id", current.holdId().value())
        + ",\"cycleNumber\":"
        + current.cycleNumber()
        + ","
        + field("policyVersion", current.policyReference().value())
        + ","
        + field("status", label(current.status()))
        + ","
        + field("owner", current.owner().value())
        + ","
        + field("reason", current.reason())
        + ",\"affectedBranches\":["
        + affected
        + "]"
        + ","
        + field("startedAt", current.startedAt().toString())
        + ","
        + field("reviewAt", current.reviewAt().toString())
        + ","
        + field("expiresAt", current.expiresAt().toString())
        + ",\"extensionCount\":"
        + current.extensionCount()
        + ",\"remediations\":["
        + remediations
        + "]}";
  }

  private static String remediationJson(BranchRemediation remediation) {
    return "{"
        + field("branch", remediation.branch().name())
        + ","
        + field("summary", remediation.summary())
        + ","
        + field("supportingReference", remediation.supportingReference().value())
        + ","
        + field("recordedBy", remediation.recordedBy().value())
        + ","
        + field("recordedAt", remediation.recordedAt().toString())
        + "}";
  }

  private String summaryJson(KeyHandoverState state) {
    return requestJson(state);
  }

  private String tasksJson(KeyHandoverState state) {
    return "["
        + state.branches().values().stream()
            .map(
                task ->
                    "{"
                        + field("id", task.branch().name().toLowerCase())
                        + ","
                        + field("title", title(task.branch()))
                        + ","
                        + field("status", label(task.status()))
                        + ","
                        + field("assignedTo", task.assignedTo().map(ActorId::value).orElse(""))
                        + ","
                        + field("outcome", task.outcome().map(Enum::name).orElse(""))
                        + "}")
            .collect(java.util.stream.Collectors.joining(","))
        + "]";
  }

  private String auditJson(KeyHandoverState state) {
    return "["
        + audits.records().stream()
            .filter(record -> record.requestId().equals(state.requestId().value()))
            .map(
                record ->
                    "{"
                        + field("id", record.eventType() + "-" + record.stateVersion())
                        + ","
                        + field("actor", record.actorId())
                        + ","
                        + field("eventType", record.eventType())
                        + ","
                        + field("timestamp", record.occurredAt())
                        + ","
                        + field("correlationId", record.correlationId())
                        + ","
                        + field("causationId", record.causationId())
                        + ","
                        + field("actorRole", record.metadata("actorRole"))
                        + ","
                        + field("previousState", record.metadata("previousState"))
                        + ","
                        + field("newState", record.metadata("newState"))
                        + ","
                        + field("reason", record.metadata("reason"))
                        + "}")
            .collect(java.util.stream.Collectors.joining(","))
        + "]";
  }

  private static String field(String name, String value) {
    return "\"" + name + "\":\"" + escape(value) + "\"";
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static ApiResponse ok(String body) {
    return new ApiResponse(200, body);
  }

  private static ApiResponse notFound() {
    return new ApiResponse(404, "{\"error\":\"Not found\"}");
  }

  private static String required(Map<String, String> values, String name) {
    String value = values.get(name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    return value;
  }

  private static boolean matches(String[] actual, String... expected) {
    return Arrays.equals(actual, expected);
  }

  private static ClearanceBranch branch(String value) {
    return ClearanceBranch.valueOf(value.toUpperCase());
  }

  private static String title(ClearanceBranch branch) {
    return switch (branch) {
      case HANDOVER -> "Handover check";
      case FINANCE -> "Finance clearance";
      case LEGAL -> "Legal clearance";
    };
  }

  private static String label(Enum<?> value) {
    return label(value.name());
  }

  private static String label(String value) {
    if ("WAITING_FOR_INSPECTION".equals(value)) return "Waiting for inspection";
    if ("CLEARANCE_IN_PROGRESS".equals(value)) return "Clearance in progress";
    if ("EXCEPTION_APPROVAL_REQUIRED".equals(value)) return "Exception approval required";
    if ("EXCEPTION_REJECTED".equals(value)) return "Exception rejected";
    if ("NOTIFICATION_FAILED".equals(value)) return "Notification failed";
    if ("HOLD".equals(value)) return "On hold";
    if ("NOT_STARTED".equals(value)) return "Not started";
    return java.util.Arrays.stream(value.toLowerCase().split("_"))
        .map(
            part ->
                part.isEmpty() ? part : Character.toUpperCase(part.charAt(0)) + part.substring(1))
        .collect(java.util.stream.Collectors.joining(" "));
  }

  private static Actor actorForBranch(ClearanceBranch branch) {
    return switch (branch) {
      case HANDOVER -> handoverActor();
      case FINANCE -> financeActor();
      case LEGAL -> legalActor();
    };
  }

  private static Actor actor(Map<String, String> parameters) {
    return actorForRole(required(parameters, "actor"));
  }

  private static Actor assignee(Map<String, String> parameters, Actor fallback) {
    String role = parameters.get("assignee");
    return role == null || role.isBlank() ? fallback : actorForRole(role);
  }

  private static Actor actorForRole(String role) {
    return switch (role) {
      case "requester" -> submitter();
      case "handoverOfficer" -> handoverActor();
      case "financeOfficer" -> financeActor();
      case "legalOfficer" -> legalActor();
      case "teamHead" -> teamHead();
      case "processOwner" -> processOwner();
      default -> throw new IllegalArgumentException("Unknown local development identity");
    };
  }

  private static Actor actorFor(TeamOrRoleRef role) {
    return switch (role.value()) {
      case "handover-role" -> handoverActor();
      case "finance-role" -> financeActor();
      case "legal-role" -> legalActor();
      default -> submitter();
    };
  }

  private static Actor submitter() {
    return actor(
        "synthetic-front-desk",
        EnumSet.of(Permission.SUBMIT_REQUEST, Permission.VIEW_TASK),
        Set.of("submit-scope"),
        "submit-role");
  }

  private static Actor handoverActor() {
    return actor(
        "synthetic-handover-reviewer",
        EnumSet.of(Permission.CLAIM_TASK, Permission.COMPLETE_TASK, Permission.VIEW_TASK),
        Set.of("handover-scope"),
        "handover-role");
  }

  private static Actor financeActor() {
    return actor(
        "synthetic-finance-reviewer",
        EnumSet.of(Permission.CLAIM_TASK, Permission.COMPLETE_TASK, Permission.VIEW_TASK),
        Set.of("finance-scope"),
        "finance-role");
  }

  private static Actor legalActor() {
    return actor(
        "synthetic-legal-reviewer",
        EnumSet.of(Permission.CLAIM_TASK, Permission.COMPLETE_TASK, Permission.VIEW_TASK),
        Set.of("legal-scope"),
        "legal-role");
  }

  private static Actor reassigner() {
    return actor(
        "synthetic-reassigner", EnumSet.of(Permission.REASSIGN_TASK), Set.of(), "operations-role");
  }

  private static Actor teamHead() {
    return actor(
        "synthetic-team-head",
        EnumSet.of(Permission.REASSIGN_TASK, Permission.EMERGENCY_REASSIGN, Permission.VIEW_TASK),
        Set.of(),
        "operations-role");
  }

  private static Actor processOwner() {
    return actor(
        "synthetic-process-owner",
        EnumSet.of(
            Permission.SUBMIT_REQUEST,
            Permission.REASSIGN_TASK,
            Permission.EMERGENCY_REASSIGN,
            Permission.RETRY_NOTIFICATION,
            Permission.DECIDE_EXCEPTION,
            Permission.MANAGE_HOLD,
            Permission.VIEW_TASK),
        Set.of(),
        "process-owner-role");
  }

  private static Actor retryActor() {
    return actor(
        "synthetic-notification-retry",
        EnumSet.of(Permission.RETRY_NOTIFICATION),
        Set.of(),
        "operations-role");
  }

  private static Actor actor(
      String id, Set<Permission> permissions, Set<String> scopes, String role) {
    return new Actor(new ActorId(id), permissions, scopes, Set.of(new TeamOrRoleRef(role)));
  }

  private static CorrelationId correlation(String number) {
    return new CorrelationId("corr-" + number);
  }

  private static CorrelationId correlation(Map<String, String> parameters, String number) {
    String value = parameters.get("correlationId");
    return new CorrelationId(value == null || value.isBlank() ? "corr-" + number : value);
  }

  private static CausationId causation(String number, String action) {
    return new CausationId("cause-" + number + "-" + action);
  }

  private static CausationId causation(
      Map<String, String> parameters, String number, String action) {
    String value = parameters.get("causationId");
    return new CausationId(
        value == null || value.isBlank() ? "cause-" + number + "-" + action : value);
  }

  private static int integer(Map<String, String> values, String name) {
    try {
      return Integer.parseInt(required(values, name));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(name + " must be a number");
    }
  }

  private static DomainVersion expectedVersion(Map<String, String> values, KeyHandoverState state) {
    String value = values.get("expectedStateVersion");
    return new DomainVersion(
        value == null || value.isBlank()
            ? state.stateVersion().value()
            : integer(values, "expectedStateVersion"));
  }

  private int nextSequence() {
    return store.states().stream()
            .map(state -> state.businessKey().value())
            .filter(number -> number.matches("KH-\\d+"))
            .mapToInt(number -> Integer.parseInt(number.substring(3)))
            .max()
            .orElse(104)
        + 1;
  }

  private static SlicePolicies policies() {
    Map<ClearanceBranch, HumanTaskPolicy> policies = new EnumMap<>(ClearanceBranch.class);
    policies.put(ClearanceBranch.HANDOVER, policy("handover-role", "handover", "handover-scope"));
    policies.put(ClearanceBranch.FINANCE, policy("finance-role", "finance", "finance-scope"));
    policies.put(ClearanceBranch.LEGAL, policy("legal-role", "legal", "legal-scope"));
    return new SlicePolicies(
        policies, new PolicyRef("synthetic-decision-policy-v1"), 2, Duration.ZERO);
  }

  private static HumanTaskPolicy policy(String role, String prefix, String scope) {
    return new HumanTaskPolicy(
        new TeamOrRoleRef(role),
        AssignmentMode.MANUAL,
        new PolicyRef(prefix + "-assignment-v1"),
        new PolicyRef(prefix + "-sla-v1"),
        new PolicyRef(prefix + "-escalation-v1"),
        1,
        Set.of(scope));
  }

  public record ApiResponse(int status, String body) {}

  private static final class DemoInspectionConnector implements InspectionConnector {
    private final Set<String> waiting = new HashSet<>();

    void setWaiting(String property, boolean value) {
      if (value) waiting.add(property);
      else waiting.remove(property);
    }

    public InspectionStatus inspectionStatus(PropertyReference property) {
      return new InspectionStatus(!waiting.contains(property.value()), Optional.empty());
    }

    public ChildWorkflowId startInspectionChildWorkflow(
        PropertyReference property, IdempotencyKey key) {
      return new ChildWorkflowId("synthetic-child-" + key.value());
    }
  }

  private static final class DemoFinanceConnector implements FinanceConnector {
    public FinanceClearance financeClearance(PropertyReference property, OwnerReference owner) {
      return new FinanceClearance(
          BigDecimal.ZERO,
          ClearanceOutcome.GREEN,
          new EvidenceReference("synthetic-finance-" + property.value()));
    }
  }

  private static final class DemoLegalConnector implements LegalConnector {
    public EvidenceReference legalEvidence(PropertyReference property, OwnerReference owner) {
      return new EvidenceReference("synthetic-legal-" + property.value());
    }
  }

  private static final class DemoNotificationConnector implements NotificationConnector {
    private boolean fail;

    void failNext() {
      fail = true;
    }

    public void sendReleaseAuthorization(
        KeyReleaseAuthorization authorization, IdempotencyKey key) {
      if (fail) {
        fail = false;
        throw new IllegalStateException("synthetic notification delivery failure");
      }
    }
  }

  private static final class PersistentDemoAuditSink implements AuditSink {
    private final Path journal;
    private final List<AuditView> records = new ArrayList<>();

    PersistentDemoAuditSink(Path journal) {
      this.journal = journal;
      load();
    }

    public synchronized void emit(AuditRecord record) {
      AuditView view =
          new AuditView(
              record.requestId().value(),
              record.eventType(),
              record.stateVersion().value(),
              record.actorId().value(),
              record.occurredAt().toString(),
              record.correlationId().value(),
              record.causationId().value(),
              metadata(record.metadata()));
      records.add(view);
      append(view);
    }

    synchronized List<AuditView> records() {
      return List.copyOf(records);
    }

    private void load() {
      try {
        if (!Files.exists(journal)) return;
        for (String line : Files.readAllLines(journal)) {
          String[] values = line.split("\\t", -1);
          if (values.length == 7 || values.length == 8) records.add(AuditView.from(values));
        }
      } catch (java.io.IOException exception) {
        throw new IllegalStateException("Unable to load local audit journal", exception);
      }
    }

    private void append(AuditView view) {
      try {
        Path parent = journal.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(
            journal,
            view.serialized() + System.lineSeparator(),
            java.nio.charset.StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND);
      } catch (java.io.IOException exception) {
        throw new IllegalStateException("Unable to persist local audit journal", exception);
      }
    }
  }

  private record AuditView(
      String requestId,
      String eventType,
      int stateVersion,
      String actorId,
      String occurredAt,
      String correlationId,
      String causationId,
      String metadata) {
    static AuditView from(String[] values) {
      return new AuditView(
          values[0],
          values[1],
          Integer.parseInt(values[2]),
          values[3],
          values[4],
          values[5],
          values[6],
          values.length == 8 ? values[7] : "");
    }

    String metadata(String name) {
      for (String entry : metadata.split(";")) {
        String[] values = entry.split("=", 2);
        if (values.length == 2 && values[0].equals(name)) return values[1];
      }
      return "";
    }

    String serialized() {
      return String.join(
          "\t",
          requestId,
          eventType,
          String.valueOf(stateVersion),
          actorId,
          occurredAt,
          correlationId,
          causationId,
          metadata);
    }
  }

  private static String metadata(Map<String, String> values) {
    return values.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> entry.getKey() + "=" + entry.getValue().replace(";", ",").replace("=", ":"))
        .collect(java.util.stream.Collectors.joining(";"));
  }

  private static final class DemoDecisionService implements DecisionService {
    private final PolicyRef policy;

    DemoDecisionService(PolicyRef policy) {
      this.policy = policy;
    }

    public FinalDecision decide(
        KeyHandoverState state,
        List<EvidenceReference> evidence,
        CorrelationId correlation,
        CausationId causation) {
      Map<ClearanceBranch, ClearanceOutcome> outcomes = new EnumMap<>(ClearanceBranch.class);
      state
          .branches()
          .forEach((branch, task) -> outcomes.put(branch, task.outcome().orElseThrow()));
      FinalAction action =
          outcomes.containsValue(ClearanceOutcome.RED)
              ? FinalAction.HOLD
              : outcomes.containsValue(ClearanceOutcome.AMBER)
                  ? FinalAction.EXCEPTION_APPROVAL_REQUIRED
                  : FinalAction.AUTHORIZE_RELEASE;
      return new FinalDecision(action, outcomes, policy, evidence, correlation, causation);
    }
  }
}
