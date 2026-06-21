package com.sevenewf.workflow.domain.keyhandover;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.*;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.*;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.*;
import com.sevenewf.workflow.domain.keyhandover.hold.*;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  private final AutomaticAssignmentService automaticAssignmentService;
  private final SlicePolicies policies;
  private final HoldPolicy holdPolicy;
  private final BusinessCalendar businessCalendar;

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
      AutomaticAssignmentService automaticAssignmentService,
      SlicePolicies policies) {
    this(
        propertyConnector,
        ownerIdentityConnector,
        inspectionConnector,
        financeConnector,
        legalConnector,
        notificationConnector,
        evidenceStore,
        decisionService,
        auditSink,
        clock,
        stateStore,
        authorizationService,
        retryScheduler,
        automaticAssignmentService,
        policies,
        new LocalKeyHandoverHoldPolicy(),
        (start, businessDays) -> start.plus(Duration.ofDays(businessDays.value())));
  }

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
      AutomaticAssignmentService automaticAssignmentService,
      SlicePolicies policies,
      HoldPolicy holdPolicy,
      BusinessCalendar businessCalendar) {
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
    this.automaticAssignmentService = automaticAssignmentService;
    this.policies = policies;
    this.holdPolicy = holdPolicy;
    this.businessCalendar = businessCalendar;
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
            Optional.empty(),
            List.of(),
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
            branchesWithHandoverOpened(state, clock.now()),
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
                "HandoverTaskOpened",
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
    if (current.assignedTo().filter(actor.actorId()::equals).isPresent()) return state;
    if (current.assignedTo().isPresent())
      throw new AuthorizationDeniedException("Task is already assigned to another actor");
    if (current.taskPolicy().assignmentMode() == AssignmentMode.AUTOMATIC)
      throw new ValidationFailedException("Automatic tasks cannot be claimed manually");
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
    enforceSegregationOfDuties(state, current, command.newAssignee().actorId());
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
    enforceSegregationOfDuties(state, current, command.newAssignee().actorId());
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
    if (command.branch() != ClearanceBranch.HANDOVER)
      throw new ValidationFailedException(
          "Finance and Legal branches require connector-backed completion operations");
    return completeHumanBranch(command);
  }

  private KeyHandoverState completeHumanBranch(BranchCompletion command) {
    authorizationService.require(command.completedBy(), Permission.COMPLETE_TASK);
    KeyHandoverState state = requireState(command.requestId());
    requireClearanceInProgress(state);
    BranchState current = requireBranch(state, command.branch());
    if (current.status() == BranchStatus.COMPLETED) {
      if (current.outcome().orElseThrow() == command.outcome()
          && current.evidenceReferences().equals(command.evidenceReferences())) return state;
      stateStore.appendPendingAudit(
          audit(
              "ConflictingDuplicateCompletionRejected",
              state,
              command.completedBy().actorId(),
              command.correlationId(),
              command.causationId(),
              command.evidenceReferences(),
              Map.of("branch", command.branch().name())));
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
    return completeHumanBranch(
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
      ClearanceOutcome outcome,
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
    return completeHumanBranch(
        new BranchCompletion(
            requestId,
            ClearanceBranch.LEGAL,
            outcome,
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
    if (notification.status() == NotificationDeliveryStatus.DELIVERED) {
      appendAudit(
          audit(
              "DuplicateNotificationRetryAccepted",
              state,
              actor.actorId(),
              correlationId,
              causationId,
              state.authorization().orElseThrow().evidenceReferences(),
              Map.of("attemptCount", Integer.toString(notification.attemptCount()))));
      return state;
    }
    if (notification.status() != NotificationDeliveryStatus.FAILED)
      throw new ValidationFailedException(
          "Notification retry is not available for this request state");
    if (notification.attemptCount() >= policies.maxConnectorAttempts()) {
      KeyHandoverState exhausted =
          next(
              state,
              RequestStatus.AUTHORIZED,
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
            RequestStatus.AUTHORIZED,
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

  public void recordNotificationFailureSimulation(
      KeyHandoverRequestId requestId,
      Actor actor,
      CorrelationId correlationId,
      CausationId causationId) {
    KeyHandoverState state = requireState(requestId);
    try {
      authorizationService.require(actor, Permission.RETRY_NOTIFICATION);
    } catch (RuntimeException exception) {
      appendAudit(
          audit(
              "UnauthorizedNotificationFailureSimulationAttempt",
              state,
              actor.actorId(),
              correlationId,
              causationId,
              List.of(),
              Map.of()));
      throw exception;
    }
    appendAudit(
        audit(
            "NotificationFailureSimulationEnabled",
            state,
            actor.actorId(),
            correlationId,
            causationId,
            List.of(),
            Map.of()));
  }

  public KeyHandoverState decideException(ExceptionDecisionCommand command) {
    KeyHandoverState state = requireState(command.requestId());
    if (state.exceptionDecision().isPresent()) {
      ExceptionDecision existing = state.exceptionDecision().orElseThrow();
      if (existing.decision() == command.decision()
          && existing.actorId().equals(command.actor().actorId())
          && existing.reason().equals(command.reason())) {
        appendAudit(
            audit(
                "DuplicateExceptionDecisionAccepted",
                state,
                command.actor().actorId(),
                command.correlationId(),
                command.causationId(),
                List.of(),
                exceptionMetadata(state, state, command)));
        return state;
      }
      appendAudit(
          audit(
              "ConflictingDuplicateExceptionDecisionRejected",
              state,
              command.actor().actorId(),
              command.correlationId(),
              command.causationId(),
              List.of(),
              exceptionMetadata(state, state, command)));
      throw new ConflictingExceptionDecisionException("Conflicting duplicate exception decision");
    }
    requireVersion(state, command.expectedStateVersion());
    requireExceptionApprover(state, command);
    if (state.status() != RequestStatus.EXCEPTION_APPROVAL_REQUIRED)
      throw new ValidationFailedException(
          "Exception approval is not available for this request state");

    ExceptionDecision decision =
        new ExceptionDecision(
            command.actor().actorId(),
            actorRole(command.actor()),
            command.decision(),
            command.reason(),
            clock.now(),
            command.correlationId(),
            command.causationId());
    if (command.decision() == ExceptionDecisionType.REJECT_EXCEPTION) {
      KeyHandoverState rejected =
          next(
              state,
              RequestStatus.EXCEPTION_REJECTED,
              Optional.of(decision),
              Optional.empty(),
              Optional.empty());
      return commit(
          rejected,
          state.stateVersion(),
          List.of(
              audit(
                  "ExceptionApprovalRequested",
                  rejected,
                  command.actor().actorId(),
                  command.correlationId(),
                  command.causationId(),
                  List.of(),
                  exceptionMetadata(state, rejected, command)),
              audit(
                  "ExceptionRejected",
                  rejected,
                  command.actor().actorId(),
                  command.correlationId(),
                  command.causationId(),
                  List.of(),
                  exceptionMetadata(state, rejected, command))));
    }

    KeyReleaseAuthorization authorization =
        new KeyReleaseAuthorization(
            new AuthorizationId("release-" + state.requestId().value()),
            state.requestId(),
            state.finalDecision().orElseThrow().evidenceReferences(),
            clock.now());
    NotificationState notification =
        new NotificationState(
            new IdempotencyKey("notify-" + authorization.authorizationId().value()),
            NotificationDeliveryStatus.PENDING,
            0,
            Optional.empty());
    KeyHandoverState approved =
        next(
            state,
            RequestStatus.AUTHORIZED,
            Optional.of(decision),
            Optional.of(authorization),
            Optional.of(notification));
    KeyHandoverState saved =
        commit(
            approved,
            state.stateVersion(),
            List.of(
                audit(
                    "ExceptionApprovalRequested",
                    approved,
                    command.actor().actorId(),
                    command.correlationId(),
                    command.causationId(),
                    List.of(),
                    exceptionMetadata(state, approved, command)),
                audit(
                    "ExceptionApproved",
                    approved,
                    command.actor().actorId(),
                    command.correlationId(),
                    command.causationId(),
                    authorization.evidenceReferences(),
                    exceptionMetadata(state, approved, command)),
                audit(
                    "AuthorizationCreated",
                    approved,
                    command.actor().actorId(),
                    command.correlationId(),
                    command.causationId(),
                    authorization.evidenceReferences(),
                    exceptionMetadata(state, approved, command)),
                audit(
                    "NotificationTriggered",
                    approved,
                    command.actor().actorId(),
                    command.correlationId(),
                    command.causationId(),
                    authorization.evidenceReferences(),
                    exceptionMetadata(state, approved, command))));
    return deliverNotification(
        saved, command.actor(), command.correlationId(), command.causationId());
  }

  public KeyHandoverState recordHoldRemediation(
      KeyHandoverRequestId requestId,
      ClearanceBranch branch,
      String summary,
      EvidenceReference supportingReference,
      Actor actor,
      DomainVersion expectedVersion,
      CorrelationId correlationId,
      CausationId causationId) {
    KeyHandoverState state = requireState(requestId);
    requireHoldManager(state, actor, correlationId, causationId);
    requireVersion(state, expectedVersion);
    HoldRecord hold = activeHold(state);
    if (!hold.affectedBranches().contains(branch))
      throw new ValidationFailedException("Remediation is only available for affected branches");
    BranchRemediation remediation =
        new BranchRemediation(branch, summary, supportingReference, actor.actorId(), clock.now());
    Map<ClearanceBranch, BranchRemediation> remediations = new EnumMap<>(ClearanceBranch.class);
    remediations.putAll(hold.remediationByBranch());
    BranchRemediation existing = remediations.put(branch, remediation);
    if (remediation.equals(existing)) {
      appendAudit(
          audit(
              "DuplicateHoldCommandAccepted",
              state,
              actor.actorId(),
              correlationId,
              causationId,
              List.of(supportingReference),
              Map.of("action", "remediation")));
      return state;
    }
    HoldLifecycleStatus status =
        remediations.keySet().containsAll(hold.affectedBranches())
            ? HoldLifecycleStatus.RESOLUTION_RECORDED
            : hold.status();
    HoldRecord updated =
        copyHold(hold, status, hold.expiresAt(), hold.extensionCount(), remediations);
    KeyHandoverState next =
        nextWithHolds(
            state,
            RequestStatus.HOLD,
            state.branches(),
            state.finalDecision(),
            replaceHold(state, updated));
    return commit(
        next,
        state.stateVersion(),
        List.of(
            audit(
                "HoldResolutionRecorded",
                next,
                actor.actorId(),
                correlationId,
                causationId,
                List.of(supportingReference),
                Map.of("branch", branch.name()))));
  }

  public KeyHandoverState extendHold(
      KeyHandoverRequestId requestId,
      BusinessDays extensionDuration,
      Actor actor,
      DomainVersion expectedVersion,
      CorrelationId correlationId,
      CausationId causationId) {
    KeyHandoverState state = requireState(requestId);
    requireHoldManager(state, actor, correlationId, causationId);
    requireVersion(state, expectedVersion);
    HoldRecord hold = activeHold(state);
    int extensionCount = hold.extensionCount() + 1;
    HoldTiming.validateExtensionCount(extensionCount, holdPolicy);
    Instant expiry =
        HoldTiming.calculateExtendedExpiryAt(
            hold.expiresAt(), extensionDuration, holdPolicy, businessCalendar);
    HoldRecord updated =
        copyHold(
            hold, HoldLifecycleStatus.EXTENDED, expiry, extensionCount, hold.remediationByBranch());
    KeyHandoverState next =
        nextWithHolds(
            state,
            RequestStatus.HOLD,
            state.branches(),
            state.finalDecision(),
            replaceHold(state, updated));
    return commit(
        next,
        state.stateVersion(),
        List.of(
            audit(
                "HoldExtended",
                next,
                actor.actorId(),
                correlationId,
                causationId,
                List.of(),
                Map.of("extensionCount", Integer.toString(extensionCount)))));
  }

  public KeyHandoverState resumeHold(
      KeyHandoverRequestId requestId,
      Actor actor,
      DomainVersion expectedVersion,
      CorrelationId correlationId,
      CausationId causationId) {
    KeyHandoverState state = requireState(requestId);
    requireHoldManager(state, actor, correlationId, causationId);
    requireVersion(state, expectedVersion);
    HoldRecord hold = activeHold(state);
    if (!hold.remediationByBranch().keySet().containsAll(hold.affectedBranches()))
      throw new ValidationFailedException("Remediation is required for every affected branch");
    if (!holdPolicy.allowsResume(hold.status()) || HoldTiming.isExpired(clock.now(), hold))
      throw new ValidationFailedException("Expired hold must be extended before resume");
    Map<ClearanceBranch, BranchState> branches = mutableBranches(state);
    for (ClearanceBranch branch : hold.affectedBranches())
      branches.put(branch, openBranch(branch, clock.now()));
    HoldRecord resumed =
        copyHold(
            hold,
            HoldLifecycleStatus.RESUMED,
            hold.expiresAt(),
            hold.extensionCount(),
            hold.remediationByBranch());
    KeyHandoverState next =
        nextWithHolds(
            state,
            RequestStatus.CLEARANCE_IN_PROGRESS,
            branches,
            Optional.empty(),
            replaceHold(state, resumed));
    return commit(
        next,
        state.stateVersion(),
        List.of(
            audit(
                "HoldResumeRequested",
                next,
                actor.actorId(),
                correlationId,
                causationId,
                List.of(),
                Map.of()),
            audit(
                "HoldResumed",
                next,
                actor.actorId(),
                correlationId,
                causationId,
                List.of(),
                Map.of())));
  }

  public KeyHandoverState rejectHold(
      KeyHandoverRequestId requestId,
      Actor actor,
      DomainVersion expectedVersion,
      CorrelationId correlationId,
      CausationId causationId) {
    return completeHoldTerminalAction(
        requestId,
        actor,
        expectedVersion,
        correlationId,
        causationId,
        HoldLifecycleStatus.REJECTED,
        RequestStatus.HOLD_REJECTED,
        "HoldRejected");
  }

  public KeyHandoverState cancelHold(
      KeyHandoverRequestId requestId,
      Actor actor,
      DomainVersion expectedVersion,
      CorrelationId correlationId,
      CausationId causationId) {
    return completeHoldTerminalAction(
        requestId,
        actor,
        expectedVersion,
        correlationId,
        causationId,
        HoldLifecycleStatus.CANCELLED,
        RequestStatus.CANCELLED,
        "HoldCancelled");
  }

  public KeyHandoverState evaluateHold(
      KeyHandoverRequestId requestId,
      Actor actor,
      CorrelationId correlationId,
      CausationId causationId) {
    KeyHandoverState state = requireState(requestId);
    HoldRecord hold = activeHold(state);
    if (HoldTiming.isExpired(clock.now(), hold)) {
      HoldRecord expired =
          copyHold(
              hold,
              HoldLifecycleStatus.EXPIRED,
              hold.expiresAt(),
              hold.extensionCount(),
              hold.remediationByBranch());
      KeyHandoverState next =
          nextWithHolds(
              state,
              RequestStatus.HOLD,
              state.branches(),
              state.finalDecision(),
              replaceHold(state, expired));
      return commit(
          next,
          state.stateVersion(),
          List.of(
              audit(
                  "HoldExpired",
                  next,
                  actor.actorId(),
                  correlationId,
                  causationId,
                  List.of(),
                  Map.of())));
    }
    if (!HoldTiming.isReviewDue(clock.now(), hold)) return state;
    appendAudit(
        audit(
            "HoldReviewDue",
            state,
            actor.actorId(),
            correlationId,
            causationId,
            List.of(),
            Map.of()));
    if (holdPolicy.requiresEscalationWhenReviewDue())
      appendAudit(
          audit(
              "HoldEscalationRequired",
              state,
              actor.actorId(),
              correlationId,
              causationId,
              List.of(),
              Map.of()));
    return state;
  }

  public KeyHandoverState initializeLegacyHold(
      KeyHandoverRequestId requestId,
      Actor processOwner,
      CorrelationId correlationId,
      CausationId causationId) {
    KeyHandoverState state = requireState(requestId);
    if (state.status() != RequestStatus.HOLD || !state.holds().isEmpty()) return state;
    Set<ClearanceBranch> affectedBranches =
        state.branches().values().stream()
            .filter(
                branch ->
                    branch.status() == BranchStatus.COMPLETED
                        && branch.outcome().orElse(null) == ClearanceOutcome.RED)
            .map(BranchState::branch)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    if (affectedBranches.isEmpty()) return state;
    Instant startedAt = clock.now();
    int cycleNumber = state.holds().stream().mapToInt(HoldRecord::cycleNumber).max().orElse(0) + 1;
    HoldRecord hold =
        new HoldRecord(
            new HoldId("hold-" + state.requestId().value() + "-" + cycleNumber),
            state.requestId(),
            cycleNumber,
            holdPolicy.policyReference(),
            holdPolicy,
            HoldLifecycleStatus.ACTIVE,
            "Legacy hold initialized from RED clearance branches",
            affectedBranches,
            processOwner.actorId(),
            processOwner.actorId(),
            startedAt,
            HoldTiming.calculateReviewAt(startedAt, holdPolicy, businessCalendar),
            HoldTiming.calculateInitialExpiryAt(startedAt, holdPolicy, businessCalendar),
            0,
            Map.of(),
            new DomainVersion(state.stateVersion().value() + 1),
            correlationId,
            causationId);
    KeyHandoverState next =
        nextWithHolds(
            state,
            RequestStatus.HOLD,
            state.branches(),
            state.finalDecision(),
            appendHold(state.holds(), hold));
    return commit(
        next,
        state.stateVersion(),
        List.of(
            audit(
                "LegacyHoldInitialized",
                next,
                processOwner.actorId(),
                correlationId,
                causationId,
                List.of(),
                Map.of(
                    "holdId",
                    hold.holdId().value(),
                    "affectedBranches",
                    affectedBranches.stream()
                        .map(Enum::name)
                        .sorted()
                        .collect(java.util.stream.Collectors.joining(",")),
                    "policyVersion",
                    hold.policyReference().value(),
                    "previousState",
                    state.status().name(),
                    "newState",
                    next.status().name()))));
  }

  public void deliverPendingAudits() {
    for (AuditRecord audit : stateStore.pendingAudits()) {
      auditSink.emit(audit);
      stateStore.markAuditDelivered(audit);
    }
  }

  private void requireExceptionApprover(KeyHandoverState state, ExceptionDecisionCommand command) {
    try {
      authorizationService.require(command.actor(), Permission.DECIDE_EXCEPTION);
    } catch (RuntimeException exception) {
      appendAudit(
          audit(
              "UnauthorizedExceptionDecisionAttempt",
              state,
              command.actor().actorId(),
              command.correlationId(),
              command.causationId(),
              List.of(),
              exceptionMetadata(state, state, command)));
      throw exception;
    }
  }

  private void appendAudit(AuditRecord audit) {
    stateStore.appendPendingAudit(audit);
    deliverPendingAudits();
  }

  private KeyHandoverState completeHoldTerminalAction(
      KeyHandoverRequestId requestId,
      Actor actor,
      DomainVersion expectedVersion,
      CorrelationId correlationId,
      CausationId causationId,
      HoldLifecycleStatus holdStatus,
      RequestStatus requestStatus,
      String eventType) {
    KeyHandoverState state = requireState(requestId);
    requireHoldManager(state, actor, correlationId, causationId);
    requireVersion(state, expectedVersion);
    HoldRecord hold = activeHold(state);
    HoldRecord updated =
        copyHold(
            hold, holdStatus, hold.expiresAt(), hold.extensionCount(), hold.remediationByBranch());
    KeyHandoverState next =
        nextWithHolds(
            state,
            requestStatus,
            state.branches(),
            state.finalDecision(),
            replaceHold(state, updated));
    return commit(
        next,
        state.stateVersion(),
        List.of(
            audit(
                eventType,
                next,
                actor.actorId(),
                correlationId,
                causationId,
                List.of(),
                Map.of())));
  }

  private void requireHoldManager(
      KeyHandoverState state, Actor actor, CorrelationId correlationId, CausationId causationId) {
    try {
      authorizationService.require(actor, Permission.MANAGE_HOLD);
      boolean isProcessOwner =
          actor.eligibleTeamsOrRoles().stream()
              .map(TeamOrRoleRef::value)
              .anyMatch(
                  value -> value.equals("process-owner-role") || value.equals("process-owner"));
      if (!isProcessOwner
          || !holdPolicy.eligibleHoldManagerRoles().contains(HoldRole.PROCESS_OWNER))
        throw new AuthorizationDeniedException("Only Process Owner may manage holds");
    } catch (RuntimeException exception) {
      appendAudit(
          audit(
              "UnauthorizedHoldAction",
              state,
              actor.actorId(),
              correlationId,
              causationId,
              List.of(),
              Map.of()));
      throw exception;
    }
  }

  private static HoldRecord activeHold(KeyHandoverState state) {
    if (state.status() != RequestStatus.HOLD)
      throw new ValidationFailedException(
          "Hold management is not available for this request state");
    return state.holds().stream()
        .filter(
            hold ->
                hold.status() != HoldLifecycleStatus.RESUMED
                    && hold.status() != HoldLifecycleStatus.REJECTED
                    && hold.status() != HoldLifecycleStatus.CANCELLED)
        .reduce((first, second) -> second)
        .orElseThrow(() -> new ValidationFailedException("No active hold exists"));
  }

  private static HoldRecord copyHold(
      HoldRecord hold,
      HoldLifecycleStatus status,
      Instant expiresAt,
      int extensionCount,
      Map<ClearanceBranch, BranchRemediation> remediations) {
    return new HoldRecord(
        hold.holdId(),
        hold.requestId(),
        hold.cycleNumber(),
        hold.policyReference(),
        hold.policy(),
        status,
        hold.reason(),
        hold.affectedBranches(),
        hold.owner(),
        hold.createdBy(),
        hold.startedAt(),
        hold.reviewAt(),
        expiresAt,
        extensionCount,
        remediations,
        hold.stateVersion(),
        hold.correlationId(),
        hold.causationId());
  }

  private static List<HoldRecord> appendHold(List<HoldRecord> holds, HoldRecord hold) {
    java.util.ArrayList<HoldRecord> result = new java.util.ArrayList<>(holds);
    result.add(hold);
    return List.copyOf(result);
  }

  private static List<HoldRecord> replaceHold(KeyHandoverState state, HoldRecord updated) {
    return state.holds().stream()
        .map(hold -> hold.holdId().equals(updated.holdId()) ? updated : hold)
        .toList();
  }

  private static TeamOrRoleRef actorRole(Actor actor) {
    return actor.eligibleTeamsOrRoles().stream()
        .sorted(java.util.Comparator.comparing(TeamOrRoleRef::value))
        .findFirst()
        .orElseThrow(() -> new AuthorizationDeniedException("Actor role is required"));
  }

  private static Map<String, String> exceptionMetadata(
      KeyHandoverState previous, KeyHandoverState next, ExceptionDecisionCommand command) {
    return Map.of(
        "actorRole", actorRole(command.actor()).value(),
        "decision", command.decision().name(),
        "previousState", previous.status().name(),
        "newState", next.status().name(),
        "reason", command.reason());
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
    List<HoldRecord> holds = state.holds();
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
    if (decision.action() == FinalAction.HOLD) {
      Set<ClearanceBranch> affectedBranches =
          state.branches().values().stream()
              .filter(branch -> branch.outcome().orElseThrow() == ClearanceOutcome.RED)
              .map(BranchState::branch)
              .collect(java.util.stream.Collectors.toUnmodifiableSet());
      Instant startedAt = clock.now();
      HoldRecord hold =
          new HoldRecord(
              new HoldId("hold-" + state.requestId().value() + "-" + (state.holds().size() + 1)),
              state.requestId(),
              state.holds().size() + 1,
              holdPolicy.policyReference(),
              holdPolicy,
              HoldLifecycleStatus.ACTIVE,
              "One or more clearance branches are RED",
              affectedBranches,
              actor.actorId(),
              actor.actorId(),
              startedAt,
              HoldTiming.calculateReviewAt(startedAt, holdPolicy, businessCalendar),
              HoldTiming.calculateInitialExpiryAt(startedAt, holdPolicy, businessCalendar),
              0,
              Map.of(),
              new DomainVersion(state.stateVersion().value() + 1),
              correlationId,
              causationId);
      holds = appendHold(state.holds(), hold);
    }
    KeyHandoverState next =
        decision.action() == FinalAction.HOLD
            ? nextWithHolds(state, status, state.branches(), Optional.of(decision), holds)
            : next(
                state,
                status,
                state.inspectionStatus(),
                state.inspectionChildWorkflowId(),
                state.branches(),
                Optional.of(decision),
                authorization,
                notification);
    List<AuditRecord> decisionAudits = new java.util.ArrayList<>();
    decisionAudits.add(
        audit(
            "FinalDecisionApplied",
            next,
            actor.actorId(),
            correlationId,
            causationId,
            evidence,
            Map.of()));
    if (decision.action() == FinalAction.HOLD)
      decisionAudits.add(
          audit(
              "HoldCreated",
              next,
              actor.actorId(),
              correlationId,
              causationId,
              List.of(),
              Map.of("holdId", next.holds().getLast().holdId().value())));
    KeyHandoverState saved = commit(next, state.stateVersion(), List.copyOf(decisionAudits));
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
              RequestStatus.AUTHORIZED,
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
      branches.put(branch, openBranch(branch, now));
    return branches;
  }

  private Map<ClearanceBranch, BranchState> branchesWithHandoverOpened(
      KeyHandoverState state, Instant now) {
    Map<ClearanceBranch, BranchState> branches = new EnumMap<>(ClearanceBranch.class);
    if (state.branches().containsKey(ClearanceBranch.FINANCE))
      branches.put(ClearanceBranch.FINANCE, state.branches().get(ClearanceBranch.FINANCE));
    if (state.branches().containsKey(ClearanceBranch.LEGAL))
      branches.put(ClearanceBranch.LEGAL, state.branches().get(ClearanceBranch.LEGAL));
    branches.put(ClearanceBranch.HANDOVER, openBranch(ClearanceBranch.HANDOVER, now));
    return branches;
  }

  private BranchState openBranch(ClearanceBranch branch, Instant now) {
    HumanTaskPolicy policy = policies.taskPolicies().get(branch);
    Optional<ActorId> assignee = Optional.empty();
    BranchStatus status = BranchStatus.OPEN;
    if (policy.assignmentMode() == AssignmentMode.AUTOMATIC) {
      Actor automaticallyAssigned = automaticAssignmentService.assign(policy);
      enforceEligibleAndAuthorized(policy, automaticallyAssigned);
      assignee = Optional.of(automaticallyAssigned.actorId());
      status = BranchStatus.CLAIMED;
    }
    return new BranchState(
        branch,
        status,
        policy,
        assignee,
        Optional.empty(),
        Optional.empty(),
        List.of(),
        now,
        Optional.empty(),
        Optional.empty());
  }

  private void enforceEligibleAndAuthorized(HumanTaskPolicy policy, Actor actor) {
    if (!actor.eligibleTeamsOrRoles().contains(policy.eligibleTeamOrRole()))
      throw new AuthorizationDeniedException("Actor is not eligible for task policy");
    if (!actor.authorityScopes().containsAll(policy.requiredAuthorityScopes()))
      throw new AuthorizationDeniedException("Actor does not hold required authority scopes");
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
        status,
        inspection,
        child,
        branches,
        decision,
        state.exceptionDecision(),
        authorization,
        notification,
        state.holds(),
        clock.now());
  }

  private KeyHandoverState next(
      KeyHandoverState state,
      RequestStatus status,
      Optional<ExceptionDecision> exceptionDecision,
      Optional<KeyReleaseAuthorization> authorization,
      Optional<NotificationState> notification) {
    return state.next(
        status,
        state.inspectionStatus(),
        state.inspectionChildWorkflowId(),
        state.branches(),
        state.finalDecision(),
        exceptionDecision,
        authorization,
        notification,
        state.holds(),
        clock.now());
  }

  private KeyHandoverState nextWithHolds(
      KeyHandoverState state,
      RequestStatus status,
      Map<ClearanceBranch, BranchState> branches,
      Optional<FinalDecision> decision,
      List<HoldRecord> holds) {
    return state.next(
        status,
        state.inspectionStatus(),
        state.inspectionChildWorkflowId(),
        branches,
        decision,
        state.exceptionDecision(),
        Optional.empty(),
        Optional.empty(),
        holds,
        clock.now());
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
