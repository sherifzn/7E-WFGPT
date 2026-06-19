package com.sevenewf.workflow.domain.keyhandover;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.*;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.*;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.*;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public final class KeyHandoverApplicationService {
  private final PropertyConnector propertyConnector;
  private final OwnerIdentityConnector ownerIdentityConnector;
  private final InspectionConnector inspectionConnector;
  private final FinanceConnector financeConnector;
  private final LegalConnector legalConnector;
  private final NotificationConnector notificationConnector;
  private final EvidenceStore evidenceStore;
  private final DecisionService decisionService;
  private final AuditSink auditSink;
  private final Clock clock;
  private final KeyHandoverStateStore stateStore;
  private final AuthorizationService authorizationService;
  private final RetryScheduler retryScheduler;
  private final SlicePolicies policies;

  public KeyHandoverApplicationService(
      PropertyConnector propertyConnector,
      OwnerIdentityConnector ownerIdentityConnector,
      InspectionConnector inspectionConnector,
      FinanceConnector financeConnector,
      LegalConnector legalConnector,
      NotificationConnector notificationConnector,
      EvidenceStore evidenceStore,
      DecisionService decisionService,
      AuditSink auditSink,
      Clock clock,
      KeyHandoverStateStore stateStore,
      AuthorizationService authorizationService,
      RetryScheduler retryScheduler,
      SlicePolicies policies) {
    this.propertyConnector = propertyConnector;
    this.ownerIdentityConnector = ownerIdentityConnector;
    this.inspectionConnector = inspectionConnector;
    this.financeConnector = financeConnector;
    this.legalConnector = legalConnector;
    this.notificationConnector = notificationConnector;
    this.evidenceStore = evidenceStore;
    this.decisionService = decisionService;
    this.auditSink = auditSink;
    this.clock = clock;
    this.stateStore = stateStore;
    this.authorizationService = authorizationService;
    this.retryScheduler = retryScheduler;
    this.policies = policies;
  }

  public KeyHandoverState submit(KeyHandoverSubmission submission) {
    authorizationService.require(submission.submittedBy(), Permission.SUBMIT_REQUEST);
    Optional<KeyHandoverState> existing = stateStore.findByBusinessKey(submission.businessKey());
    if (existing.isPresent()) return existing.get();
    validateReferences(submission);
    InspectionStatus inspectionStatus =
        retry(
            () -> inspectionConnector.inspectionStatus(submission.propertyReference()),
            "inspectionStatus");
    Optional<ChildWorkflowId> child = inspectionStatus.existingChildWorkflowId();
    RequestStatus status =
        inspectionStatus.validInspectionExists()
            ? RequestStatus.CLEARANCE_IN_PROGRESS
            : RequestStatus.WAITING_FOR_INSPECTION;
    if (!inspectionStatus.validInspectionExists() && child.isEmpty())
      child =
          Optional.of(
              retry(
                  () ->
                      inspectionConnector.startInspectionChildWorkflow(
                          submission.propertyReference(),
                          inspectionChildKey(submission.businessKey())),
                  "startInspectionChildWorkflow"));
    Instant now = clock.now();
    KeyHandoverState state =
        new KeyHandoverState(
            requestId(submission.businessKey()),
            new DomainVersion(1),
            submission.businessKey(),
            submission.propertyReference(),
            submission.ownerReference(),
            status,
            inspectionStatus,
            child,
            inspectionStatus.validInspectionExists() ? openBranches(now) : Map.of(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            now);
    List<AuditRecord> audits =
        List.of(
            audit(
                "KeyHandoverSubmitted",
                state,
                submission.submittedBy().actorId(),
                submission.correlationId(),
                submission.causationId(),
                List.of(),
                Map.of()),
            audit(
                inspectionStatus.validInspectionExists()
                    ? "ClearanceTasksOpened"
                    : "InspectionChildWorkflowLinked",
                state,
                submission.submittedBy().actorId(),
                submission.correlationId(),
                submission.causationId(),
                List.of(),
                Map.of()));
    KeyHandoverState inserted = stateStore.insertIfAbsent(state, audits);
    deliverPendingAudits();
    return inserted;
  }

  public KeyHandoverState resumeAfterInspection(InspectionAvailable command) {
    authorizationService.require(command.actor(), Permission.SUBMIT_REQUEST);
    KeyHandoverState state = requireState(command.requestId());
    if (state.status() == RequestStatus.CLEARANCE_IN_PROGRESS) return state;
    requireVersion(state, command.expectedStateVersion());
    if (state.status() != RequestStatus.WAITING_FOR_INSPECTION)
      throw new ValidationFailedException("Inspection cannot resume this request state");
    InspectionStatus valid = new InspectionStatus(true, state.inspectionChildWorkflowId());
    KeyHandoverState next =
        next(
            state,
            RequestStatus.CLEARANCE_IN_PROGRESS,
            valid,
            state.inspectionChildWorkflowId(),
            openBranches(clock.now()),
            state.finalDecision(),
            state.authorization(),
            state.notificationState());
    return commit(
        next,
        state.stateVersion(),
        List.of(
            audit(
                "InspectionAvailable",
                next,
                command.actor().actorId(),
                command.correlationId(),
                command.causationId(),
                List.of(command.inspectionEvidence()),
                Map.of()),
            audit(
                "ClearanceTasksOpened",
                next,
                command.actor().actorId(),
                command.correlationId(),
                command.causationId(),
                List.of(),
                Map.of())));
  }

  public KeyHandoverState claimTask(
      KeyHandoverRequestId requestId,
      ClearanceBranch branch,
      Actor actor,
      DomainVersion expectedVersion,
      CorrelationId correlationId,
      CausationId causationId) {
    authorizationService.require(actor, Permission.CLAIM_TASK);
    KeyHandoverState state = requireState(requestId);
    requireVersion(state, expectedVersion);
    requireClearanceInProgress(state);
    BranchState current = requireBranch(state, branch);
    enforceEligibleAndAuthorized(current, actor);
    enforceSegregationOfDuties(state, current, actor.actorId());
    if (current.status() == BranchStatus.COMPLETED)
      throw new ValidationFailedException("Completed task cannot be claimed");
    Map<ClearanceBranch, BranchState> branches = mutableBranches(state);
    branches.put(branch, current.assignedTo(actor.actorId()));
    KeyHandoverState next =
        next(
            state,
            state.status(),
            state.inspectionStatus(),
            state.inspectionChildWorkflowId(),
            branches,
            state.finalDecision(),
            state.authorization(),
            state.notificationState());
    return commit(
        next,
        state.stateVersion(),
        List.of(
            audit(
                "TaskClaimed",
                next,
                actor.actorId(),
                correlationId,
                causationId,
                List.of(),
                Map.of())));
  }

  public KeyHandoverState reassignTask(TaskReassignment command) {
    authorizationService.require(command.reassignedBy(), Permission.REASSIGN_TASK);
    KeyHandoverState state = requireState(command.requestId());
    requireVersion(state, command.expectedStateVersion());
    requireClearanceInProgress(state);
    BranchState current = requireBranch(state, command.branch());
    enforceEligibleAndAuthorized(current, command.newAssignee());
    if (current.status() == BranchStatus.COMPLETED)
      throw new ValidationFailedException("Completed task cannot be reassigned");
    Map<ClearanceBranch, BranchState> branches = mutableBranches(state);
    branches.put(command.branch(), current.assignedTo(command.newAssignee().actorId()));
    KeyHandoverState next =
        next(
            state,
            state.status(),
            state.inspectionStatus(),
            state.inspectionChildWorkflowId(),
            branches,
            state.finalDecision(),
            state.authorization(),
            state.notificationState());
    return commit(
        next,
        state.stateVersion(),
        List.of(
            audit(
                "TaskReassigned",
                next,
                command.reassignedBy().actorId(),
                command.correlationId(),
                command.causationId(),
                List.of(),
                Map.of(
                    "previousAssignee",
                    current.assignedTo().map(ActorId::value).orElse("unassigned"),
                    "newAssignee",
                    command.newAssignee().actorId().value()))));
  }

  public KeyHandoverState emergencyReassign(EmergencyReassignment command) {
    authorizationService.require(command.teamHead(), Permission.EMERGENCY_REASSIGN);
    if (!command.expiresAt().isAfter(clock.now()))
      throw new ValidationFailedException("Emergency reassignment authority has expired");
    KeyHandoverState state = requireState(command.requestId());
    requireVersion(state, command.expectedStateVersion());
    requireClearanceInProgress(state);
    BranchState current = requireBranch(state, command.branch());
    enforceEligibleAndAuthorized(current, command.newAssignee());
    Map<ClearanceBranch, BranchState> branches = mutableBranches(state);
    branches.put(command.branch(), current.assignedTo(command.newAssignee().actorId()));
    KeyHandoverState next =
        next(
            state,
            state.status(),
            state.inspectionStatus(),
            state.inspectionChildWorkflowId(),
            branches,
            state.finalDecision(),
            state.authorization(),
            state.notificationState());
    return commit(
        next,
        state.stateVersion(),
        List.of(
            audit(
                "EmergencyTaskReassigned",
                next,
                command.teamHead().actorId(),
                command.correlationId(),
                command.causationId(),
                List.of(),
                Map.of(
                    "reason",
                    command.reason(),
                    "expiry",
                    command.expiresAt().toString(),
                    "previousAssignee",
                    current.assignedTo().map(ActorId::value).orElse("unassigned"),
                    "newAssignee",
                    command.newAssignee().actorId().value()))));
  }

  public KeyHandoverState completeBranch(BranchCompletion command) {
    authorizationService.require(command.completedBy(), Permission.COMPLETE_TASK);
    KeyHandoverState state = requireState(command.requestId());
    requireClearanceInProgress(state);
    BranchState current = requireBranch(state, command.branch());
    if (current.status() == BranchStatus.COMPLETED) {
      if (current.outcome().orElseThrow() == command.outcome()
          && current.evidenceReferences().equals(command.evidenceReferences())) return state;
      throw new ConflictingDuplicateCompletionException(
          "Conflicting duplicate completion rejected");
    }
    requireVersion(state, command.expectedStateVersion());
    enforceEligibleAndAuthorized(current, command.completedBy());
    requireAssignedActor(current, command.completedBy().actorId());
    enforceSegregationOfDuties(state, current, command.completedBy().actorId());
    EvidenceReference stored =
        retry(
            () ->
                evidenceStore.storeSyntheticEvidence(
                    "branch-" + command.branch().name().toLowerCase(),
                    new IdempotencyKey(
                        "evidence-" + command.requestId().value() + "-" + command.branch().name())),
            "storeEvidence");
    List<EvidenceReference> evidence = appendEvidence(command.evidenceReferences(), stored);
    Map<ClearanceBranch, BranchState> branches = mutableBranches(state);
    branches.put(
        command.branch(),
        current.completed(command.outcome(), evidence, command.completedBy().actorId()));
    KeyHandoverState next =
        next(
            state,
            state.status(),
            state.inspectionStatus(),
            state.inspectionChildWorkflowId(),
            branches,
            state.finalDecision(),
            state.authorization(),
            state.notificationState());
    KeyHandoverState saved =
        commit(
            next,
            state.stateVersion(),
            List.of(
                audit(
                    "BranchCompleted",
                    next,
                    command.completedBy().actorId(),
                    command.correlationId(),
                    command.causationId(),
                    evidence,
                    Map.of())));
    return maybeDecide(
        saved, command.completedBy(), command.correlationId(), command.causationId());
  }

  public KeyHandoverState completeFinanceBranch(
      KeyHandoverRequestId requestId,
      Actor actor,
      DomainVersion expectedVersion,
      CorrelationId correlationId,
      CausationId causationId) {
    KeyHandoverState state = requireState(requestId);
    requireVersion(state, expectedVersion);
    requireClearanceInProgress(state);
    authorizationService.require(actor, Permission.COMPLETE_TASK);
    BranchState branch = requireBranch(state, ClearanceBranch.FINANCE);
    enforceEligibleAndAuthorized(branch, actor);
    requireAssignedActor(branch, actor.actorId());
    enforceSegregationOfDuties(state, branch, actor.actorId());
    FinanceClearance finance =
        retry(
            () ->
                financeConnector.financeClearance(
                    state.propertyReference(), state.ownerReference()),
            "financeClearance");
    return completeBranch(
        new BranchCompletion(
            requestId,
            ClearanceBranch.FINANCE,
            finance.outcome(),
            List.of(finance.evidence()),
            actor,
            expectedVersion,
            correlationId,
            causationId));
  }

  public KeyHandoverState completeLegalBranch(
      KeyHandoverRequestId requestId,
      Actor actor,
      DomainVersion expectedVersion,
      CorrelationId correlationId,
      CausationId causationId) {
    KeyHandoverState state = requireState(requestId);
    requireVersion(state, expectedVersion);
    requireClearanceInProgress(state);
    authorizationService.require(actor, Permission.COMPLETE_TASK);
    BranchState branch = requireBranch(state, ClearanceBranch.LEGAL);
    enforceEligibleAndAuthorized(branch, actor);
    requireAssignedActor(branch, actor.actorId());
    enforceSegregationOfDuties(state, branch, actor.actorId());
    EvidenceReference evidence =
        retry(
            () -> legalConnector.legalEvidence(state.propertyReference(), state.ownerReference()),
            "legalEvidence");
    return completeBranch(
        new BranchCompletion(
            requestId,
            ClearanceBranch.LEGAL,
            ClearanceOutcome.GREEN,
            List.of(evidence),
            actor,
            expectedVersion,
            correlationId,
            causationId));
  }

  public KeyHandoverState evaluateSla(
      KeyHandoverRequestId requestId,
      Duration warningAfter,
      Duration breachAfter,
      Actor actor,
      CorrelationId correlationId,
      CausationId causationId) {
    KeyHandoverState state = requireState(requestId);
    requireClearanceInProgress(state);
    if (breachAfter.compareTo(warningAfter) <= 0)
      throw new ValidationFailedException("breachAfter must be after warningAfter");
    Instant now = clock.now();
    Map<ClearanceBranch, BranchState> branches = mutableBranches(state);
    List<AuditRecord> audits = new java.util.ArrayList<>();
    for (BranchState branch : state.branches().values()) {
      Optional<Instant> warning = branch.slaWarningAt();
      Optional<Instant> breach = branch.slaBreachedAt();
      if (branch.status() != BranchStatus.COMPLETED
          && warning.isEmpty()
          && !now.isBefore(branch.openedAt().plus(warningAfter))) {
        warning = Optional.of(now);
      }
      if (branch.status() != BranchStatus.COMPLETED
          && breach.isEmpty()
          && !now.isBefore(branch.openedAt().plus(breachAfter))) {
        breach = Optional.of(now);
      }
      BranchState updated = branch.withSla(warning, breach);
      branches.put(branch.branch(), updated);
      if (branch.slaWarningAt().isEmpty() && warning.isPresent())
        audits.add(
            audit(
                "SlaWarningRecorded",
                state,
                actor.actorId(),
                correlationId,
                causationId,
                List.of(),
                Map.of("branch", branch.branch().name())));
      if (branch.slaBreachedAt().isEmpty() && breach.isPresent())
        audits.add(
            audit(
                "SlaBreachRecorded",
                state,
                actor.actorId(),
                correlationId,
                causationId,
                List.of(),
                Map.of("branch", branch.branch().name())));
    }
    if (audits.isEmpty()) return state;
    KeyHandoverState next =
        next(
            state,
            state.status(),
            state.inspectionStatus(),
            state.inspectionChildWorkflowId(),
            branches,
            state.finalDecision(),
            state.authorization(),
            state.notificationState());
    List<AuditRecord> versioned =
        audits.stream()
            .map(
                audit ->
                    audit(
                        audit.eventType(),
                        next,
                        actor.actorId(),
                        correlationId,
                        causationId,
                        audit.evidenceReferences(),
                        audit.metadata()))
            .toList();
    return commit(next, state.stateVersion(), versioned);
  }

  public KeyHandoverState retryNotification(
      KeyHandoverRequestId requestId,
      Actor actor,
      DomainVersion expectedVersion,
      CorrelationId correlationId,
      CausationId causationId) {
    authorizationService.require(actor, Permission.RETRY_NOTIFICATION);
    KeyHandoverState state = requireState(requestId);
    requireVersion(state, expectedVersion);
    if (state.authorization().isEmpty() || state.notificationState().isEmpty())
      throw new ValidationFailedException("No notification recovery is required");
    NotificationState notification = state.notificationState().orElseThrow();
    if (notification.attemptCount() >= policies.maxConnectorAttempts()) {
      KeyHandoverState exhausted =
          next(
              state,
              RequestStatus.NOTIFICATION_FAILED,
              state.inspectionStatus(),
              state.inspectionChildWorkflowId(),
              state.branches(),
              state.finalDecision(),
              state.authorization(),
              state.notificationState());
      commit(
          exhausted,
          state.stateVersion(),
          List.of(
              audit(
                  "NotificationRetryExhausted",
                  exhausted,
                  actor.actorId(),
                  correlationId,
                  causationId,
                  state.authorization().orElseThrow().evidenceReferences(),
                  Map.of("attemptCount", Integer.toString(notification.attemptCount())))));
      throw new RetryExhaustedException("notification recovery exhausted retry attempts", null);
    }
    NotificationState pending =
        new NotificationState(
            notification.idempotencyKey(),
            NotificationDeliveryStatus.PENDING,
            notification.attemptCount(),
            notification.lastFailureReference());
    KeyHandoverState recoveryStarted =
        next(
            state,
            RequestStatus.NOTIFICATION_FAILED,
            state.inspectionStatus(),
            state.inspectionChildWorkflowId(),
            state.branches(),
            state.finalDecision(),
            state.authorization(),
            Optional.of(pending));
    KeyHandoverState saved =
        commit(
            recoveryStarted,
            state.stateVersion(),
            List.of(
                audit(
                    "NotificationRetryRequested",
                    recoveryStarted,
                    actor.actorId(),
                    correlationId,
                    causationId,
                    state.authorization().orElseThrow().evidenceReferences(),
                    Map.of("attempt", Integer.toString(notification.attemptCount() + 1)))));
    return deliverNotification(saved, actor, correlationId, causationId);
  }

  public void deliverPendingAudits() {
    for (AuditRecord audit : stateStore.pendingAudits()) {
      auditSink.emit(audit);
      stateStore.markAuditDelivered(audit);
    }
  }

  public KeyHandoverState viewTask(
      KeyHandoverRequestId requestId, ClearanceBranch branch, Actor actor) {
    authorizationService.require(actor, Permission.VIEW_TASK);
    KeyHandoverState state = requireState(requestId);
    requireClearanceInProgress(state);
    BranchState task = requireBranch(state, branch);
    enforceEligibleAndAuthorized(task, actor);
    return state;
  }

  private KeyHandoverState maybeDecide(
      KeyHandoverState state, Actor actor, CorrelationId correlationId, CausationId causationId) {
    if (state.finalDecision().isPresent()
        || state.branches().values().stream()
            .anyMatch(branch -> branch.status() != BranchStatus.COMPLETED)) return state;
    List<EvidenceReference> evidence =
        state.branches().values().stream()
            .flatMap(branch -> branch.evidenceReferences().stream())
            .toList();
    FinalDecision decision = decisionService.decide(state, evidence, correlationId, causationId);
    RequestStatus status =
        switch (decision.action()) {
          case AUTHORIZE_RELEASE -> RequestStatus.AUTHORIZED;
          case HOLD -> RequestStatus.HOLD;
          case EXCEPTION_APPROVAL_REQUIRED -> RequestStatus.EXCEPTION_APPROVAL_REQUIRED;
        };
    Optional<KeyReleaseAuthorization> authorization = Optional.empty();
    Optional<NotificationState> notification = Optional.empty();
    if (decision.action() == FinalAction.AUTHORIZE_RELEASE) {
      KeyReleaseAuthorization release =
          new KeyReleaseAuthorization(
              new AuthorizationId("release-" + state.requestId().value()),
              state.requestId(),
              evidence,
              clock.now());
      authorization = Optional.of(release);
      notification =
          Optional.of(
              new NotificationState(
                  new IdempotencyKey("notify-" + release.authorizationId().value()),
                  NotificationDeliveryStatus.PENDING,
                  0,
                  Optional.empty()));
    }
    KeyHandoverState next =
        next(
            state,
            status,
            state.inspectionStatus(),
            state.inspectionChildWorkflowId(),
            state.branches(),
            Optional.of(decision),
            authorization,
            notification);
    KeyHandoverState saved =
        commit(
            next,
            state.stateVersion(),
            List.of(
                audit(
                    "FinalDecisionApplied",
                    next,
                    actor.actorId(),
                    correlationId,
                    causationId,
                    evidence,
                    Map.of())));
    return authorization.isPresent()
        ? deliverNotification(saved, actor, correlationId, causationId)
        : saved;
  }

  private KeyHandoverState deliverNotification(
      KeyHandoverState state, Actor actor, CorrelationId correlationId, CausationId causationId) {
    NotificationState notification = state.notificationState().orElseThrow();
    KeyReleaseAuthorization authorization = state.authorization().orElseThrow();
    try {
      notificationConnector.sendReleaseAuthorization(authorization, notification.idempotencyKey());
      NotificationState delivered =
          new NotificationState(
              notification.idempotencyKey(),
              NotificationDeliveryStatus.DELIVERED,
              notification.attemptCount() + 1,
              Optional.empty());
      KeyHandoverState next =
          next(
              state,
              RequestStatus.AUTHORIZED,
              state.inspectionStatus(),
              state.inspectionChildWorkflowId(),
              state.branches(),
              state.finalDecision(),
              state.authorization(),
              Optional.of(delivered));
      return commit(
          next,
          state.stateVersion(),
          List.of(
              audit(
                  "NotificationDelivered",
                  next,
                  actor.actorId(),
                  correlationId,
                  causationId,
                  authorization.evidenceReferences(),
                  Map.of("attempt", Integer.toString(delivered.attemptCount())))));
    } catch (RuntimeException exception) {
      NotificationState failed =
          new NotificationState(
              notification.idempotencyKey(),
              NotificationDeliveryStatus.FAILED,
              notification.attemptCount() + 1,
              Optional.of(
                  new FailureReference(
                      "notification-failure-"
                          + state.requestId().value()
                          + "-"
                          + (notification.attemptCount() + 1))));
      KeyHandoverState next =
          next(
              state,
              RequestStatus.NOTIFICATION_FAILED,
              state.inspectionStatus(),
              state.inspectionChildWorkflowId(),
              state.branches(),
              state.finalDecision(),
              state.authorization(),
              Optional.of(failed));
      commit(
          next,
          state.stateVersion(),
          List.of(
              audit(
                  "NotificationFailed",
                  next,
                  actor.actorId(),
                  correlationId,
                  causationId,
                  authorization.evidenceReferences(),
                  Map.of(
                      "attempt",
                      Integer.toString(failed.attemptCount()),
                      "failureReference",
                      failed.lastFailureReference().orElseThrow().value()))));
      throw exception;
    }
  }

  private void validateReferences(KeyHandoverSubmission submission) {
    if (!retry(
        () -> propertyConnector.propertyExists(submission.propertyReference()), "propertyExists"))
      throw new ValidationFailedException("Unknown property reference");
    if (!retry(
        () ->
            ownerIdentityConnector.ownerMatchesProperty(
                submission.ownerReference(), submission.propertyReference()),
        "ownerMatchesProperty"))
      throw new ValidationFailedException("Owner reference does not match property");
  }

  private KeyHandoverState commit(
      KeyHandoverState next, DomainVersion expected, List<AuditRecord> audits) {
    KeyHandoverState saved = stateStore.commit(next, expected, audits);
    deliverPendingAudits();
    return saved;
  }

  private KeyHandoverState requireState(KeyHandoverRequestId requestId) {
    return stateStore
        .findById(requestId)
        .orElseThrow(() -> new ValidationFailedException("Unknown key handover request"));
  }

  private void requireVersion(KeyHandoverState state, DomainVersion expected) {
    if (!state.stateVersion().equals(expected))
      throw new OptimisticStateConflictException("State version conflict");
  }

  private void requireClearanceInProgress(KeyHandoverState state) {
    if (state.status() == RequestStatus.WAITING_FOR_INSPECTION)
      throw new ValidationFailedException("Inspection barrier has not been satisfied");
    if (state.status() != RequestStatus.CLEARANCE_IN_PROGRESS)
      throw new ValidationFailedException("Clearance work is not available for this request state");
  }

  private BranchState requireBranch(KeyHandoverState state, ClearanceBranch branch) {
    BranchState branchState = state.branches().get(branch);
    if (branchState == null) throw new ValidationFailedException("Unknown clearance branch");
    return branchState;
  }

  private void enforceEligibleAndAuthorized(BranchState branch, Actor actor) {
    if (!actor.eligibleTeamsOrRoles().contains(branch.taskPolicy().eligibleTeamOrRole()))
      throw new AuthorizationDeniedException("Actor is not eligible for task policy");
    if (!actor.authorityScopes().containsAll(branch.taskPolicy().requiredAuthorityScopes()))
      throw new AuthorizationDeniedException("Actor does not hold required authority scopes");
  }

  private void requireAssignedActor(BranchState branch, ActorId actorId) {
    if (branch.taskPolicy().assignmentMode() == AssignmentMode.MANUAL
        && !branch.assignedTo().filter(actorId::equals).isPresent())
      throw new AuthorizationDeniedException("Task must be completed by its assigned actor");
  }

  private void enforceSegregationOfDuties(
      KeyHandoverState state, BranchState branch, ActorId actorId) {
    if (branch.completedBy().filter(actorId::equals).isPresent()
        || state.branches().values().stream()
            .filter(other -> other.branch() != branch.branch())
            .anyMatch(other -> other.completedBy().filter(actorId::equals).isPresent()))
      throw new AuthorizationDeniedException("Segregation of duties violation");
  }

  private Map<ClearanceBranch, BranchState> openBranches(Instant now) {
    Map<ClearanceBranch, BranchState> branches = new EnumMap<>(ClearanceBranch.class);
    for (ClearanceBranch branch : ClearanceBranch.values())
      branches.put(
          branch,
          new BranchState(
              branch,
              BranchStatus.OPEN,
              policies.taskPolicies().get(branch),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              List.of(),
              now,
              Optional.empty(),
              Optional.empty()));
    return branches;
  }

  private KeyHandoverState next(
      KeyHandoverState state,
      RequestStatus status,
      InspectionStatus inspection,
      Optional<ChildWorkflowId> child,
      Map<ClearanceBranch, BranchState> branches,
      Optional<FinalDecision> decision,
      Optional<KeyReleaseAuthorization> authorization,
      Optional<NotificationState> notification) {
    return state.next(
        status, inspection, child, branches, decision, authorization, notification, clock.now());
  }

  private AuditRecord audit(
      String eventType,
      KeyHandoverState state,
      ActorId actorId,
      CorrelationId correlationId,
      CausationId causationId,
      List<EvidenceReference> evidence,
      Map<String, String> metadata) {
    return new AuditRecord(
        eventType,
        state.requestId(),
        state.stateVersion(),
        correlationId,
        causationId,
        actorId,
        clock.now(),
        evidence,
        metadata);
  }

  private <T> T retry(Supplier<T> supplier, String operation) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= policies.maxConnectorAttempts(); attempt++)
      try {
        return supplier.get();
      } catch (TransientConnectorException exception) {
        last = exception;
        if (attempt < policies.maxConnectorAttempts())
          retryScheduler.backoff(policies.retryBackoff(), attempt, operation);
      }
    throw new RetryExhaustedException(operation + " exhausted retry attempts", last);
  }

  private static List<EvidenceReference> appendEvidence(
      List<EvidenceReference> evidence, EvidenceReference stored) {
    java.util.ArrayList<EvidenceReference> combined = new java.util.ArrayList<>(evidence);
    combined.add(stored);
    return List.copyOf(combined);
  }

  private static Map<ClearanceBranch, BranchState> mutableBranches(KeyHandoverState state) {
    return new EnumMap<>(state.branches());
  }

  private static KeyHandoverRequestId requestId(BusinessKey businessKey) {
    return new KeyHandoverRequestId("khr-" + businessKey.value());
  }

  private static IdempotencyKey inspectionChildKey(BusinessKey businessKey) {
    return new IdempotencyKey("inspection-child-" + businessKey.value());
  }
}
