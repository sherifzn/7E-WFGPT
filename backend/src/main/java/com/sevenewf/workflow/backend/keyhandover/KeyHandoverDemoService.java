package com.sevenewf.workflow.backend.keyhandover;

import com.sevenewf.workflow.adapters.keyhandover.synthetic.SyntheticKeyHandoverAdapters.PathBackedKeyHandoverStateStore;
import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverApplicationService;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.*;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverState;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.*;
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
  private final Path dataDirectory;
  private int sequence;

  public KeyHandoverDemoService() {
    this(Path.of("local-data"));
  }

  public KeyHandoverDemoService(Path dataDirectory) {
    this.dataDirectory = dataDirectory;
    store = new PathBackedKeyHandoverStateStore(dataDirectory.resolve("key-handover-state.bin"));
    audits = new PersistentDemoAuditSink(dataDirectory.resolve("audit-events.tsv"));
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
      if (!"POST".equals(method)) return notFound();
      return ok(requestJson(action(state, Arrays.copyOfRange(parts, 1, parts.length), parameters)));
    } catch (SecurityException exception) {
      return new ApiResponse(403, "{\"error\":\"" + escape(exception.getMessage()) + "\"}");
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
    return new ApiResponse(201, requestJson(state));
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
        .orElseThrow(() -> new IllegalArgumentException("Unknown request"));
  }

  private String listJson() {
    return "["
        + store.states().stream()
            .map(this::summaryJson)
            .collect(java.util.stream.Collectors.joining(","))
        + "]";
  }

  private String requestJson(KeyHandoverState state) {
    return "{"
        + field("requestNumber", state.businessKey().value())
        + ","
        + field("property", state.propertyReference().value())
        + ","
        + field("owner", state.ownerReference().value())
        + ","
        + field("status", label(state.status()))
        + ",\"stateVersion\":"
        + state.stateVersion().value()
        + ","
        + field(
            "inspection",
            state.inspectionStatus().validInspectionExists() ? "Available" : "Waiting")
        + ","
        + field(
            "finalDecision",
            state.finalDecision().map(decision -> label(decision.action())).orElse(""))
        + ","
        + field(
            "notification",
            state.notificationState().map(value -> label(value.status())).orElse("Not started"))
        + ","
        + field("lastUpdated", state.updatedAt().toString())
        + ",\"tasks\":"
        + tasksJson(state)
        + ",\"audit\":"
        + auditJson(state)
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
            Permission.VIEW_TASK),
        Set.of(),
        "operations-role");
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

  private static CausationId causation(String number, String action) {
    return new CausationId("cause-" + number + "-" + action);
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
              record.causationId().value());
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
          if (values.length == 7) records.add(AuditView.from(values));
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
      String causationId) {
    static AuditView from(String[] values) {
      return new AuditView(
          values[0],
          values[1],
          Integer.parseInt(values[2]),
          values[3],
          values[4],
          values[5],
          values[6]);
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
          causationId);
    }
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
