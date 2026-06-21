package com.sevenewf.workflow.domain.inspection;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EvidenceReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverRequestId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PropertyReference;
import java.time.Instant;
import java.time.Period;
import java.util.List;
import java.util.Optional;

public record InspectionProcess(
    InspectionProcessId id,
    InspectionBusinessKey businessKey,
    KeyHandoverRequestId parentRequestId,
    PropertyReference propertyReference,
    String inspectionType,
    InspectionProcessStatus status,
    DomainVersion version,
    List<InspectionAttempt> attempts,
    List<RemediationCycle> remediationCycles,
    List<InspectionTask> tasks,
    Optional<String> cancellationReason,
    CorrelationId correlationId,
    CausationId causationId,
    Instant updatedAt) {
  public InspectionProcess {
    Validation.requirePresent(id, "inspectionProcessId");
    Validation.requirePresent(businessKey, "businessKey");
    Validation.requirePresent(parentRequestId, "parentRequestId");
    Validation.requirePresent(propertyReference, "propertyReference");
    inspectionType = Validation.requireText(inspectionType, "inspectionType");
    Validation.requirePresent(status, "status");
    Validation.requirePresent(version, "version");
    attempts = List.copyOf(Validation.requirePresent(attempts, "attempts"));
    remediationCycles =
        List.copyOf(Validation.requirePresent(remediationCycles, "remediationCycles"));
    tasks = List.copyOf(Validation.requirePresent(tasks, "tasks"));
    cancellationReason = cancellationReason == null ? Optional.empty() : cancellationReason;
    Validation.requirePresent(correlationId, "correlationId");
    Validation.requirePresent(causationId, "causationId");
    Validation.requirePresent(updatedAt, "updatedAt");
  }

  public boolean hasValidPassedAttempt(Instant now) {
    Validation.requirePresent(now, "now");
    return attempts.stream().anyMatch(attempt -> attempt.isValidAt(now));
  }

  public enum InspectionProcessStatus {
    REQUESTED,
    ASSIGNED,
    IN_PROGRESS,
    PASSED,
    FAILED,
    WAITING_FOR_REMEDIATION,
    WAITING_FOR_REINSPECTION,
    CANCELLED,
    COMPLETED
  }

  public enum InspectionTaskType {
    INSPECTION,
    REMEDIATION
  }

  public enum InspectionTaskStatus {
    OPEN,
    CLAIMED,
    COMPLETED,
    CANCELLED
  }

  public enum InspectionRole {
    INSPECTION_OFFICER,
    REMEDIATION_OFFICER,
    TEAM_HEAD,
    PROCESS_OWNER
  }

  public enum InspectionResult {
    PASSED,
    FAILED
  }

  public enum RemediationStatus {
    REQUIRED,
    ASSIGNED,
    IN_PROGRESS,
    COMPLETED,
    REJECTED,
    CANCELLED
  }

  public record InspectionProcessId(String value) {
    public InspectionProcessId {
      Validation.requireText(value, "inspectionProcessId");
    }
  }

  public record InspectionBusinessKey(String value) {
    public InspectionBusinessKey {
      Validation.requireText(value, "inspectionBusinessKey");
    }

    public static InspectionBusinessKey of(
        PropertyReference propertyReference,
        String inspectionType,
        KeyHandoverRequestId parentRequestId) {
      Validation.requirePresent(propertyReference, "propertyReference");
      inspectionType = Validation.requireText(inspectionType, "inspectionType");
      Validation.requirePresent(parentRequestId, "parentRequestId");
      return new InspectionBusinessKey(
          propertyReference.value() + "|" + inspectionType + "|" + parentRequestId.value());
    }
  }

  public record InspectionAttempt(
      int number,
      InspectionResult result,
      String findings,
      EvidenceReference evidenceReference,
      Instant completedAt,
      Optional<Instant> validUntil) {
    public InspectionAttempt {
      Validation.requirePositive(number, "attemptNumber");
      Validation.requirePresent(result, "result");
      findings = Validation.requireText(findings, "findings");
      Validation.requirePresent(evidenceReference, "evidenceReference");
      Validation.requirePresent(completedAt, "completedAt");
      validUntil = validUntil == null ? Optional.empty() : validUntil;
      if (result == InspectionResult.PASSED && validUntil.isEmpty())
        throw new IllegalArgumentException("passed inspection requires validity");
      if (result == InspectionResult.FAILED && validUntil.isPresent())
        throw new IllegalArgumentException("failed inspection cannot have validity");
    }

    public boolean isValidAt(Instant now) {
      return result == InspectionResult.PASSED && validUntil.filter(now::isBefore).isPresent();
    }
  }

  public record RemediationCycle(
      int number,
      RemediationStatus status,
      Optional<String> resolutionSummary,
      Optional<EvidenceReference> remediationReference) {
    public RemediationCycle {
      Validation.requirePositive(number, "remediationCycleNumber");
      Validation.requirePresent(status, "remediationStatus");
      resolutionSummary = resolutionSummary == null ? Optional.empty() : resolutionSummary;
      remediationReference = remediationReference == null ? Optional.empty() : remediationReference;
      if (status == RemediationStatus.COMPLETED
          && (resolutionSummary.isEmpty() || remediationReference.isEmpty()))
        throw new IllegalArgumentException("completed remediation requires summary and reference");
    }
  }

  public record InspectionTask(
      String id,
      InspectionTaskType type,
      InspectionTaskStatus status,
      InspectionRole requiredRole,
      Optional<ActorId> assignee,
      Instant createdAt,
      Optional<Instant> claimedAt,
      Optional<Instant> completedAt,
      Optional<String> outcome,
      DomainVersion version,
      CorrelationId correlationId,
      CausationId causationId) {
    public InspectionTask {
      id = Validation.requireText(id, "inspectionTaskId");
      Validation.requirePresent(type, "inspectionTaskType");
      Validation.requirePresent(status, "inspectionTaskStatus");
      Validation.requirePresent(requiredRole, "requiredRole");
      assignee = assignee == null ? Optional.empty() : assignee;
      Validation.requirePresent(createdAt, "createdAt");
      claimedAt = claimedAt == null ? Optional.empty() : claimedAt;
      completedAt = completedAt == null ? Optional.empty() : completedAt;
      outcome = outcome == null ? Optional.empty() : outcome;
      Validation.requirePresent(version, "version");
      Validation.requirePresent(correlationId, "correlationId");
      Validation.requirePresent(causationId, "causationId");
    }
  }

  public static final class LocalInspectionValidityPolicy {
    public static final String VERSION = "inspection-validity-v1-local";
    private static final Period VALIDITY_PERIOD = Period.ofDays(30);

    public Instant validUntil(Instant completedAt) {
      Validation.requirePresent(completedAt, "completedAt");
      return completedAt.plus(VALIDITY_PERIOD);
    }
  }
}
