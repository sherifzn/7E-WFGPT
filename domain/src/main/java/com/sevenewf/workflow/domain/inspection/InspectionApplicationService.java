package com.sevenewf.workflow.domain.inspection;

import static com.sevenewf.workflow.domain.inspection.InspectionApplicationExceptions.AuthorizationDeniedException;
import static com.sevenewf.workflow.domain.inspection.InspectionApplicationExceptions.ConflictException;
import static com.sevenewf.workflow.domain.inspection.InspectionApplicationExceptions.ProcessNotFoundException;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus.CANCELLED;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus.IN_PROGRESS;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus.REQUESTED;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus.WAITING_FOR_REINSPECTION;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus.WAITING_FOR_REMEDIATION;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionRole.INSPECTION_OFFICER;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionRole.PROCESS_OWNER;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionRole.REMEDIATION_OFFICER;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTaskStatus.CLAIMED;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTaskStatus.COMPLETED;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTaskStatus.OPEN;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionAttempt;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionBusinessKey;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessId;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionResult;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionRole;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTask;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTaskType;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.RemediationCycle;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.RemediationStatus;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EvidenceReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverRequestId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PropertyReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class InspectionApplicationService {
  private final InspectionProcessStore store;
  private final InspectionProcess.LocalInspectionValidityPolicy validityPolicy;

  public InspectionApplicationService(InspectionProcessStore store) {
    this(store, new InspectionProcess.LocalInspectionValidityPolicy());
  }

  public InspectionApplicationService(
      InspectionProcessStore store,
      InspectionProcess.LocalInspectionValidityPolicy validityPolicy) {
    this.store = Validation.requirePresent(store, "inspectionProcessStore");
    this.validityPolicy = Validation.requirePresent(validityPolicy, "inspectionValidityPolicy");
  }

  public InspectionProcess requestOrCorrelate(InspectionRequestCommand command) {
    Validation.requirePresent(command, "inspectionRequestCommand");
    InspectionBusinessKey businessKey =
        InspectionBusinessKey.of(
            command.propertyReference(), command.inspectionType(), command.parentRequestId());
    Optional<InspectionProcess> existing = store.findByBusinessKey(businessKey);
    if (existing.isPresent() && !isTerminal(existing.get().status())) return existing.get();

    InspectionProcessId processId = new InspectionProcessId("inspection-" + businessKey.value());
    InspectionTask task = inspectionTask(processId, 1, command.metadata());
    InspectionProcess created =
        new InspectionProcess(
            processId,
            businessKey,
            command.parentRequestId(),
            command.propertyReference(),
            command.inspectionType(),
            REQUESTED,
            new DomainVersion(1),
            List.of(),
            List.of(),
            List.of(task),
            Optional.empty(),
            command.metadata().correlationId(),
            command.metadata().causationId(),
            command.metadata().commandedAt());
    return store.insertIfAbsent(created);
  }

  public InspectionProcess claimInspection(ClaimTaskCommand command) {
    return claim(command, InspectionTaskType.INSPECTION, INSPECTION_OFFICER, false, false);
  }

  public InspectionProcess claimRemediation(ClaimTaskCommand command) {
    return claim(command, InspectionTaskType.REMEDIATION, REMEDIATION_OFFICER, true, false);
  }

  public InspectionProcess claimReinspection(ClaimTaskCommand command) {
    return claim(command, InspectionTaskType.INSPECTION, INSPECTION_OFFICER, false, true);
  }

  public InspectionProcess completeInspectionPassed(CompleteInspectionCommand command) {
    requireResult(command, InspectionResult.PASSED);
    return completeInspection(command, false);
  }

  public InspectionProcess completeInspectionFailed(CompleteInspectionCommand command) {
    requireResult(command, InspectionResult.FAILED);
    return completeInspection(command, false);
  }

  public InspectionProcess completeRemediation(CompleteRemediationCommand command) {
    requireRole(command.metadata(), REMEDIATION_OFFICER);
    InspectionProcess process = requireProcess(command.processId());
    InspectionTask task = requireTask(process, command.taskId(), InspectionTaskType.REMEDIATION);
    if (isEquivalentCompletion(task, command.metadata(), "REMEDIATION_COMPLETED")) return process;
    requireVersion(process, command.metadata());
    requireClaimedBy(task, command.metadata().actorId());
    if (task.status() == COMPLETED) throw conflict("Remediation task is already completed");
    int cycleNumber = process.remediationCycles().size();
    RemediationCycle cycle = process.remediationCycles().get(cycleNumber - 1);
    if (cycle.status() != RemediationStatus.IN_PROGRESS)
      throw conflict("Remediation is not in progress");
    List<RemediationCycle> cycles = new ArrayList<>(process.remediationCycles());
    cycles.set(
        cycleNumber - 1,
        new RemediationCycle(
            cycle.number(),
            RemediationStatus.COMPLETED,
            Optional.of(command.resolutionSummary()),
            Optional.of(command.remediationReference())));
    List<InspectionTask> tasks =
        completeTask(process.tasks(), task, "REMEDIATION_COMPLETED", command.metadata());
    tasks.add(inspectionTask(process.id(), process.attempts().size() + 1, command.metadata()));
    return commit(
        process,
        WAITING_FOR_REINSPECTION,
        process.attempts(),
        cycles,
        tasks,
        Optional.empty(),
        command.metadata());
  }

  public InspectionProcess completeReinspection(CompleteInspectionCommand command) {
    return completeInspection(command, true);
  }

  public InspectionProcess cancel(CancelInspectionCommand command) {
    requireRole(command.metadata(), PROCESS_OWNER);
    InspectionProcess process = requireProcess(command.processId());
    if (process.status() == CANCELLED
        && process.cancellationReason().filter(command.reason()::equals).isPresent()
        && process.causationId().equals(command.metadata().causationId())) return process;
    requireVersion(process, command.metadata());
    if (isTerminal(process.status())) throw conflict("Inspection process is already terminal");
    return commit(
        process,
        CANCELLED,
        process.attempts(),
        process.remediationCycles(),
        process.tasks(),
        Optional.of(command.reason()),
        command.metadata());
  }

  private InspectionProcess claim(
      ClaimTaskCommand command,
      InspectionTaskType type,
      InspectionRole role,
      boolean remediation,
      boolean reinspection) {
    requireRole(command.metadata(), role);
    InspectionProcess process = requireProcess(command.processId());
    InspectionTask task = requireTask(process, command.taskId(), type);
    if (task.status() == CLAIMED
        && task.assignee().filter(command.metadata().actorId()::equals).isPresent()) return process;
    requireVersion(process, command.metadata());
    if (type == InspectionTaskType.INSPECTION
        && reinspection != (process.status() == WAITING_FOR_REINSPECTION))
      throw conflict("Inspection task does not match the requested inspection stage");
    if (task.status() != OPEN) throw conflict("Inspection task is not available for claim");
    List<InspectionTask> tasks = new ArrayList<>(process.tasks());
    tasks.set(tasks.indexOf(task), claimedTask(task, command.metadata()));
    List<RemediationCycle> cycles = process.remediationCycles();
    if (remediation) {
      cycles = new ArrayList<>(cycles);
      int cycleNumber = cycles.size() - 1;
      RemediationCycle cycle = cycles.get(cycleNumber);
      if (cycle.status() != RemediationStatus.REQUIRED)
        throw conflict("Remediation is not available for claim");
      cycles.set(
          cycleNumber,
          new RemediationCycle(
              cycle.number(), RemediationStatus.IN_PROGRESS, Optional.empty(), Optional.empty()));
    }
    return commit(
        process,
        IN_PROGRESS,
        process.attempts(),
        cycles,
        tasks,
        process.cancellationReason(),
        command.metadata());
  }

  private InspectionProcess completeInspection(
      CompleteInspectionCommand command, boolean reinspection) {
    requireRole(command.metadata(), INSPECTION_OFFICER);
    InspectionProcess process = requireProcess(command.processId());
    InspectionTask task = requireTask(process, command.taskId(), InspectionTaskType.INSPECTION);
    String outcome = command.result().name();
    if (isEquivalentCompletion(task, command.metadata(), outcome)) return process;
    requireVersion(process, command.metadata());
    requireClaimedBy(task, command.metadata().actorId());
    if (task.status() == COMPLETED) throw conflict("Inspection task is already completed");
    if (reinspection != (process.status() == IN_PROGRESS && !process.attempts().isEmpty()))
      throw conflict("Inspection task does not match the requested inspection stage");
    InspectionAttempt attempt =
        new InspectionAttempt(
            process.attempts().size() + 1,
            command.result(),
            command.findings(),
            command.evidenceReference(),
            command.metadata().commandedAt(),
            command.result() == InspectionResult.PASSED
                ? Optional.of(validityPolicy.validUntil(command.metadata().commandedAt()))
                : Optional.empty());
    List<InspectionAttempt> attempts = new ArrayList<>(process.attempts());
    attempts.add(attempt);
    List<InspectionTask> tasks = completeTask(process.tasks(), task, outcome, command.metadata());
    if (command.result() == InspectionResult.PASSED)
      return commit(
          process,
          InspectionProcessStatus.COMPLETED,
          attempts,
          process.remediationCycles(),
          tasks,
          Optional.empty(),
          command.metadata());
    List<RemediationCycle> cycles = new ArrayList<>(process.remediationCycles());
    int cycleNumber = cycles.size() + 1;
    cycles.add(
        new RemediationCycle(
            cycleNumber, RemediationStatus.REQUIRED, Optional.empty(), Optional.empty()));
    tasks.add(remediationTask(process.id(), cycleNumber, command.metadata()));
    return commit(
        process,
        WAITING_FOR_REMEDIATION,
        attempts,
        cycles,
        tasks,
        Optional.empty(),
        command.metadata());
  }

  private InspectionProcess commit(
      InspectionProcess process,
      InspectionProcessStatus status,
      List<InspectionAttempt> attempts,
      List<RemediationCycle> cycles,
      List<InspectionTask> tasks,
      Optional<String> cancellationReason,
      InspectionCommandMetadata metadata) {
    InspectionProcess next =
        new InspectionProcess(
            process.id(),
            process.businessKey(),
            process.parentRequestId(),
            process.propertyReference(),
            process.inspectionType(),
            status,
            nextVersion(process.version()),
            attempts,
            cycles,
            tasks,
            cancellationReason,
            metadata.correlationId(),
            metadata.causationId(),
            metadata.commandedAt());
    return store.commit(next, process.version());
  }

  private InspectionProcess requireProcess(InspectionProcessId processId) {
    return store
        .findById(processId)
        .orElseThrow(() -> new ProcessNotFoundException("Inspection process was not found"));
  }

  private static InspectionTask requireTask(
      InspectionProcess process, String taskId, InspectionTaskType type) {
    return process.tasks().stream()
        .filter(task -> task.id().equals(taskId) && task.type() == type)
        .findFirst()
        .orElseThrow(() -> conflict("Inspection task was not found"));
  }

  private static void requireRole(InspectionCommandMetadata metadata, InspectionRole requiredRole) {
    if (metadata.actorRole() != requiredRole)
      throw new AuthorizationDeniedException("Actor is not authorized for inspection action");
  }

  private static void requireVersion(
      InspectionProcess process, InspectionCommandMetadata metadata) {
    if (!process.version().equals(metadata.expectedProcessVersion()))
      throw conflict("Inspection process version conflict");
  }

  private static void requireClaimedBy(InspectionTask task, ActorId actorId) {
    if (task.status() != CLAIMED || task.assignee().filter(actorId::equals).isEmpty())
      throw conflict("Inspection task is not claimed by the actor");
  }

  private static void requireResult(CompleteInspectionCommand command, InspectionResult result) {
    if (command.result() != result)
      throw conflict("Inspection result does not match the requested command");
  }

  private static boolean isEquivalentCompletion(
      InspectionTask task, InspectionCommandMetadata metadata, String outcome) {
    return task.status() == COMPLETED
        && task.outcome().filter(outcome::equals).isPresent()
        && task.causationId().equals(metadata.causationId());
  }

  private static boolean isTerminal(InspectionProcessStatus status) {
    return status == CANCELLED || status == InspectionProcessStatus.COMPLETED;
  }

  private static ConflictException conflict(String message) {
    return new ConflictException(message);
  }

  private static DomainVersion nextVersion(DomainVersion version) {
    return new DomainVersion(version.value() + 1);
  }

  private static InspectionTask inspectionTask(
      InspectionProcessId processId, int attemptNumber, InspectionCommandMetadata metadata) {
    return task(
        processId.value() + "-inspection-" + attemptNumber,
        InspectionTaskType.INSPECTION,
        INSPECTION_OFFICER,
        metadata);
  }

  private static InspectionTask remediationTask(
      InspectionProcessId processId, int cycleNumber, InspectionCommandMetadata metadata) {
    return task(
        processId.value() + "-remediation-" + cycleNumber,
        InspectionTaskType.REMEDIATION,
        REMEDIATION_OFFICER,
        metadata);
  }

  private static InspectionTask task(
      String id, InspectionTaskType type, InspectionRole role, InspectionCommandMetadata metadata) {
    return new InspectionTask(
        id,
        type,
        OPEN,
        role,
        Optional.empty(),
        metadata.commandedAt(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        new DomainVersion(1),
        metadata.correlationId(),
        metadata.causationId());
  }

  private static InspectionTask claimedTask(
      InspectionTask task, InspectionCommandMetadata metadata) {
    return new InspectionTask(
        task.id(),
        task.type(),
        CLAIMED,
        task.requiredRole(),
        Optional.of(metadata.actorId()),
        task.createdAt(),
        Optional.of(metadata.commandedAt()),
        Optional.empty(),
        Optional.empty(),
        nextVersion(task.version()),
        metadata.correlationId(),
        metadata.causationId());
  }

  private static List<InspectionTask> completeTask(
      List<InspectionTask> existing,
      InspectionTask task,
      String outcome,
      InspectionCommandMetadata metadata) {
    List<InspectionTask> tasks = new ArrayList<>(existing);
    tasks.set(
        tasks.indexOf(task),
        new InspectionTask(
            task.id(),
            task.type(),
            COMPLETED,
            task.requiredRole(),
            task.assignee(),
            task.createdAt(),
            task.claimedAt(),
            Optional.of(metadata.commandedAt()),
            Optional.of(outcome),
            nextVersion(task.version()),
            metadata.correlationId(),
            metadata.causationId()));
    return tasks;
  }

  public record InspectionRequestCommand(
      KeyHandoverRequestId parentRequestId,
      PropertyReference propertyReference,
      String inspectionType,
      InspectionCommandMetadata metadata) {
    public InspectionRequestCommand {
      Validation.requirePresent(parentRequestId, "parentRequestId");
      Validation.requirePresent(propertyReference, "propertyReference");
      inspectionType = Validation.requireText(inspectionType, "inspectionType");
      Validation.requirePresent(metadata, "metadata");
    }
  }

  public record ClaimTaskCommand(
      InspectionProcessId processId, String taskId, InspectionCommandMetadata metadata) {
    public ClaimTaskCommand {
      Validation.requirePresent(processId, "inspectionProcessId");
      taskId = Validation.requireText(taskId, "inspectionTaskId");
      Validation.requirePresent(metadata, "metadata");
    }
  }

  public record CompleteInspectionCommand(
      InspectionProcessId processId,
      String taskId,
      InspectionResult result,
      String findings,
      EvidenceReference evidenceReference,
      InspectionCommandMetadata metadata) {
    public CompleteInspectionCommand {
      Validation.requirePresent(processId, "inspectionProcessId");
      taskId = Validation.requireText(taskId, "inspectionTaskId");
      Validation.requirePresent(result, "inspectionResult");
      findings = Validation.requireText(findings, "findings");
      Validation.requirePresent(evidenceReference, "evidenceReference");
      Validation.requirePresent(metadata, "metadata");
    }
  }

  public record CompleteRemediationCommand(
      InspectionProcessId processId,
      String taskId,
      String resolutionSummary,
      EvidenceReference remediationReference,
      InspectionCommandMetadata metadata) {
    public CompleteRemediationCommand {
      Validation.requirePresent(processId, "inspectionProcessId");
      taskId = Validation.requireText(taskId, "inspectionTaskId");
      resolutionSummary = Validation.requireText(resolutionSummary, "resolutionSummary");
      Validation.requirePresent(remediationReference, "remediationReference");
      Validation.requirePresent(metadata, "metadata");
    }
  }

  public record CancelInspectionCommand(
      InspectionProcessId processId, String reason, InspectionCommandMetadata metadata) {
    public CancelInspectionCommand {
      Validation.requirePresent(processId, "inspectionProcessId");
      reason = Validation.requireText(reason, "cancellationReason");
      Validation.requirePresent(metadata, "metadata");
    }
  }
}
