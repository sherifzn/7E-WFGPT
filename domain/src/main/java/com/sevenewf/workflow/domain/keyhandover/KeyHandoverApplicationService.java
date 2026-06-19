package com.sevenewf.workflow.domain.keyhandover;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.AuthorizationDeniedException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.ConflictingDuplicateCompletionException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.OptimisticStateConflictException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.RetryExhaustedException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.TransientConnectorException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.ValidationFailedException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.AuditSink;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.AuthorizationService;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.Clock;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.DecisionService;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.EvidenceStore;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.FinanceConnector;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.InspectionConnector;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.KeyHandoverStateStore;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.LegalConnector;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.NotificationConnector;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.OwnerIdentityConnector;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.PropertyConnector;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.Actor;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.AuditRecord;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.AuthorizationId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.BranchCompletion;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.BranchState;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.BranchStatus;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ChildWorkflowId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ClearanceBranch;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EmergencyReassignment;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EvidenceReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.FinalAction;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.FinalDecision;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.HumanTaskPolicy;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.IdempotencyKey;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.InspectionStatus;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverRequestId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverSubmission;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyReleaseAuthorization;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.Permission;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.RequestStatus;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.SlicePolicies;
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
    this.policies = policies;
  }

  public KeyHandoverState submit(KeyHandoverSubmission submission) {
    authorizationService.require(submission.submittedBy(), Permission.CLAIM_TASK);
    Optional<KeyHandoverState> existing = stateStore.findByBusinessKey(submission.businessKey());
    if (existing.isPresent()) {
      return existing.get();
    }

    validateReferences(submission);
    InspectionStatus inspectionStatus =
        retry(
            () -> inspectionConnector.inspectionStatus(submission.propertyReference()),
            "inspectionStatus");
    Optional<ChildWorkflowId> childWorkflowId = inspectionStatus.existingChildWorkflowId();
    RequestStatus status =
        inspectionStatus.validInspectionExists()
            ? RequestStatus.CLEARANCE_IN_PROGRESS
            : RequestStatus.WAITING_FOR_INSPECTION;
    if (!inspectionStatus.validInspectionExists() && childWorkflowId.isEmpty()) {
      IdempotencyKey key = inspectionChildKey(submission.businessKey());
      childWorkflowId =
          Optional.of(
              retry(
                  () ->
                      inspectionConnector.startInspectionChildWorkflow(
                          submission.propertyReference(), key),
                  "startInspectionChildWorkflow"));
    }

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
            childWorkflowId,
            openBranches(now),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            now);
    KeyHandoverState inserted = stateStore.insertIfAbsent(state);
    emit(
        "KeyHandoverSubmitted",
        inserted,
        submission.submittedBy().actorId(),
        submission.correlationId(),
        submission.causationId(),
        List.of());
    if (childWorkflowId.isPresent()) {
      emit(
          "InspectionChildWorkflowLinked",
          inserted,
          submission.submittedBy().actorId(),
          submission.correlationId(),
          submission.causationId(),
          List.of());
    }
    return inserted;
  }

  public KeyHandoverState claimTask(
      KeyHandoverRequestId requestId,
      ClearanceBranch branch,
      Actor actor,
      DomainVersion expectedStateVersion,
      CorrelationId correlationId,
      CausationId causationId) {
    authorizationService.require(actor, Permission.CLAIM_TASK);
    KeyHandoverState state = requireState(requestId);
    requireVersion(state, expectedStateVersion);
    BranchState branchState = requireBranch(state, branch);
    enforceSegregationOfDuties(state, branchState, actor.actorId());
    Map<ClearanceBranch, BranchState> branches = mutableBranches(state);
    branches.put(branch, branchState.claimedBy(actor.actorId()));
    KeyHandoverState saved =
        saveNext(
            state,
            state.status(),
            state.inspectionStatus(),
            state.inspectionChildWorkflowId(),
            branches,
            state.finalDecision(),
            state.authorization(),
            state.notificationIdempotencyKey());
    emit("TaskClaimed", saved, actor.actorId(), correlationId, causationId, List.of());
    return saved;
  }

  public KeyHandoverState completeBranch(BranchCompletion completion) {
    authorizationService.require(completion.completedBy(), Permission.COMPLETE_TASK);
    KeyHandoverState state = requireState(completion.requestId());
    BranchState branchState = requireBranch(state, completion.branch());

    if (branchState.status() == BranchStatus.COMPLETED) {
      if (branchState.outcome().orElseThrow() == completion.outcome()
          && branchState.evidenceReferences().equals(completion.evidenceReferences())) {
        return state;
      }
      emit(
          "ConflictingDuplicateCompletionRejected",
          state,
          completion.completedBy().actorId(),
          completion.correlationId(),
          completion.causationId(),
          completion.evidenceReferences());
      throw new ConflictingDuplicateCompletionException(
          "Conflicting duplicate completion rejected");
    }

    requireVersion(state, completion.expectedStateVersion());
    enforceSegregationOfDuties(state, branchState, completion.completedBy().actorId());

    Map<ClearanceBranch, BranchState> branches = mutableBranches(state);
    branches.put(
        completion.branch(),
        branchState.completed(
            completion.outcome(),
            completion.evidenceReferences(),
            completion.completedBy().actorId()));
    KeyHandoverState saved =
        saveNext(
            state,
            state.status(),
            state.inspectionStatus(),
            state.inspectionChildWorkflowId(),
            branches,
            state.finalDecision(),
            state.authorization(),
            state.notificationIdempotencyKey());
    emit(
        "BranchCompleted",
        saved,
        completion.completedBy().actorId(),
        completion.correlationId(),
        completion.causationId(),
        completion.evidenceReferences());
    return maybeDecide(
        saved, completion.completedBy(), completion.correlationId(), completion.causationId());
  }

  public KeyHandoverState completeFinanceBranch(
      KeyHandoverRequestId requestId,
      Actor actor,
      DomainVersion expectedStateVersion,
      CorrelationId correlationId,
      CausationId causationId) {
    KeyHandoverState state = requireState(requestId);
    KeyHandoverTypes.FinanceClearance finance =
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
            expectedStateVersion,
            correlationId,
            causationId));
  }

  public KeyHandoverState emergencyReassign(EmergencyReassignment reassignment) {
    authorizationService.require(reassignment.teamHead(), Permission.EMERGENCY_REASSIGN);
    if (!reassignment.expiresAt().isAfter(clock.now())) {
      throw new ValidationFailedException("Emergency reassignment expiry must be in the future");
    }
    KeyHandoverState state = requireState(reassignment.requestId());
    requireVersion(state, reassignment.expectedStateVersion());
    Map<ClearanceBranch, BranchState> branches = mutableBranches(state);
    branches.put(
        reassignment.branch(),
        requireBranch(state, reassignment.branch()).reassignedTo(reassignment.newAssignee()));
    KeyHandoverState saved =
        saveNext(
            state,
            state.status(),
            state.inspectionStatus(),
            state.inspectionChildWorkflowId(),
            branches,
            state.finalDecision(),
            state.authorization(),
            state.notificationIdempotencyKey());
    emit(
        "EmergencyTaskReassigned",
        saved,
        reassignment.teamHead().actorId(),
        reassignment.correlationId(),
        reassignment.causationId(),
        List.of());
    return saved;
  }

  public KeyHandoverState evaluateSla(
      KeyHandoverRequestId requestId, Duration warningAfter, Duration breachAfter) {
    KeyHandoverState state = requireState(requestId);
    if (breachAfter.compareTo(warningAfter) <= 0) {
      throw new ValidationFailedException("breachAfter must be after warningAfter");
    }
    Instant now = clock.now();
    Map<ClearanceBranch, BranchState> branches = mutableBranches(state);
    for (BranchState branch : state.branches().values()) {
      Optional<Instant> warning = branch.slaWarningAt();
      Optional<Instant> breach = branch.slaBreachedAt();
      if (branch.status() != BranchStatus.COMPLETED
          && !now.isBefore(branch.openedAt().plus(warningAfter))) {
        warning = Optional.of(now);
      }
      if (branch.status() != BranchStatus.COMPLETED
          && !now.isBefore(branch.openedAt().plus(breachAfter))) {
        breach = Optional.of(now);
      }
      branches.put(branch.branch(), branch.withSla(warning, breach));
    }
    return saveNext(
        state,
        state.status(),
        state.inspectionStatus(),
        state.inspectionChildWorkflowId(),
        branches,
        state.finalDecision(),
        state.authorization(),
        state.notificationIdempotencyKey());
  }

  public KeyHandoverState viewTask(
      KeyHandoverRequestId requestId, ClearanceBranch branch, Actor actor) {
    authorizationService.require(actor, Permission.VIEW_TASK);
    KeyHandoverState state = requireState(requestId);
    requireBranch(state, branch);
    return state;
  }

  private void validateReferences(KeyHandoverSubmission submission) {
    boolean propertyExists =
        retry(
            () -> propertyConnector.propertyExists(submission.propertyReference()),
            "propertyExists");
    if (!propertyExists) {
      throw new ValidationFailedException("Unknown property reference");
    }
    boolean ownerMatches =
        retry(
            () ->
                ownerIdentityConnector.ownerMatchesProperty(
                    submission.ownerReference(), submission.propertyReference()),
            "ownerMatchesProperty");
    if (!ownerMatches) {
      throw new ValidationFailedException("Owner reference does not match property");
    }
  }

  private KeyHandoverState maybeDecide(
      KeyHandoverState state, Actor actor, CorrelationId correlationId, CausationId causationId) {
    if (state.finalDecision().isPresent()
        || state.branches().values().stream()
            .anyMatch(branch -> branch.status() != BranchStatus.COMPLETED)) {
      return state;
    }
    List<EvidenceReference> evidence =
        state.branches().values().stream()
            .flatMap(branch -> branch.evidenceReferences().stream())
            .toList();
    FinalDecision decision = decisionService.decide(state, evidence);
    RequestStatus status =
        switch (decision.action()) {
          case AUTHORIZE_RELEASE -> RequestStatus.AUTHORIZED;
          case HOLD -> RequestStatus.HOLD;
          case EXCEPTION_APPROVAL_REQUIRED -> RequestStatus.EXCEPTION_APPROVAL_REQUIRED;
        };
    Optional<KeyReleaseAuthorization> authorization = Optional.empty();
    Optional<AuthorizationId> notificationKey = Optional.empty();
    if (decision.action() == FinalAction.AUTHORIZE_RELEASE) {
      KeyReleaseAuthorization release =
          new KeyReleaseAuthorization(
              new AuthorizationId("release-" + state.requestId().value()),
              state.requestId(),
              evidence,
              clock.now());
      authorization = Optional.of(release);
      notificationKey = Optional.of(release.authorizationId());
    }
    KeyHandoverState saved =
        saveNext(
            state,
            status,
            state.inspectionStatus(),
            state.inspectionChildWorkflowId(),
            state.branches(),
            Optional.of(decision),
            authorization,
            notificationKey);
    emit("FinalDecisionApplied", saved, actor.actorId(), correlationId, causationId, evidence);
    if (authorization.isPresent()) {
      IdempotencyKey key =
          new IdempotencyKey("notify-" + authorization.get().authorizationId().value());
      try {
        notificationConnector.sendReleaseAuthorization(authorization.get(), key);
      } catch (RuntimeException exception) {
        KeyHandoverState failed =
            saveNext(
                saved,
                RequestStatus.NOTIFICATION_FAILED,
                saved.inspectionStatus(),
                saved.inspectionChildWorkflowId(),
                saved.branches(),
                saved.finalDecision(),
                saved.authorization(),
                saved.notificationIdempotencyKey());
        emit("NotificationFailed", failed, actor.actorId(), correlationId, causationId, evidence);
        throw exception;
      }
    }
    return saved;
  }

  private KeyHandoverState requireState(KeyHandoverRequestId requestId) {
    return stateStore
        .findById(requestId)
        .orElseThrow(() -> new ValidationFailedException("Unknown key handover request"));
  }

  private void requireVersion(KeyHandoverState state, DomainVersion expected) {
    if (!state.stateVersion().equals(expected)) {
      throw new OptimisticStateConflictException("State version conflict");
    }
  }

  private BranchState requireBranch(KeyHandoverState state, ClearanceBranch branch) {
    BranchState branchState = state.branches().get(branch);
    if (branchState == null) {
      throw new ValidationFailedException("Unknown clearance branch");
    }
    return branchState;
  }

  private void enforceSegregationOfDuties(
      KeyHandoverState state, BranchState branchState, ActorId actorId) {
    if (branchState.completedBy().filter(actorId::equals).isPresent()
        || state.branches().values().stream()
            .filter(branch -> branch.branch() != branchState.branch())
            .anyMatch(branch -> branch.completedBy().filter(actorId::equals).isPresent())) {
      throw new AuthorizationDeniedException("Segregation of duties violation");
    }
  }

  private Map<ClearanceBranch, BranchState> openBranches(Instant now) {
    Map<ClearanceBranch, BranchState> branches = new EnumMap<>(ClearanceBranch.class);
    for (ClearanceBranch branch : ClearanceBranch.values()) {
      HumanTaskPolicy taskPolicy = policies.taskPolicies().get(branch);
      branches.put(
          branch,
          new BranchState(
              branch,
              BranchStatus.OPEN,
              taskPolicy,
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              List.of(),
              now,
              Optional.empty(),
              Optional.empty()));
    }
    return branches;
  }

  private KeyHandoverState saveNext(
      KeyHandoverState state,
      RequestStatus status,
      InspectionStatus inspectionStatus,
      Optional<ChildWorkflowId> inspectionChildWorkflowId,
      Map<ClearanceBranch, BranchState> branches,
      Optional<FinalDecision> finalDecision,
      Optional<KeyReleaseAuthorization> authorization,
      Optional<AuthorizationId> notificationIdempotencyKey) {
    KeyHandoverState next =
        state.next(
            status,
            inspectionStatus,
            inspectionChildWorkflowId,
            branches,
            finalDecision,
            authorization,
            notificationIdempotencyKey,
            clock.now());
    return stateStore.save(next, state.stateVersion());
  }

  private void emit(
      String eventType,
      KeyHandoverState state,
      ActorId actorId,
      CorrelationId correlationId,
      CausationId causationId,
      List<EvidenceReference> evidenceReferences) {
    auditSink.emit(
        new AuditRecord(
            eventType,
            state.requestId(),
            state.stateVersion(),
            correlationId,
            causationId,
            actorId,
            clock.now(),
            evidenceReferences));
  }

  private <T> T retry(Supplier<T> supplier, String operation) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= policies.maxConnectorAttempts(); attempt++) {
      try {
        return supplier.get();
      } catch (TransientConnectorException exception) {
        last = exception;
      }
    }
    throw new RetryExhaustedException(operation + " exhausted retry attempts", last);
  }

  private static Map<ClearanceBranch, BranchState> mutableBranches(KeyHandoverState state) {
    return new EnumMap<>(state.branches());
  }

  private static KeyHandoverRequestId requestId(KeyHandoverTypes.BusinessKey businessKey) {
    return new KeyHandoverRequestId("khr-" + businessKey.value());
  }

  private static IdempotencyKey inspectionChildKey(KeyHandoverTypes.BusinessKey businessKey) {
    return new IdempotencyKey("inspection-child-" + businessKey.value());
  }
}
