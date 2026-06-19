package com.sevenewf.workflow.domain.keyhandover;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class KeyHandoverTypes {
  private KeyHandoverTypes() {}

  public record KeyHandoverRequestId(String value) {
    public KeyHandoverRequestId {
      Validation.requireText(value, "keyHandoverRequestId");
    }
  }

  public record BusinessKey(String value) {
    public BusinessKey {
      Validation.requireText(value, "businessKey");
    }
  }

  public record PropertyReference(String value) {
    public PropertyReference {
      Validation.requireText(value, "propertyReference");
    }
  }

  public record OwnerReference(String value) {
    public OwnerReference {
      Validation.requireText(value, "ownerReference");
    }
  }

  public record EvidenceReference(String value) {
    public EvidenceReference {
      Validation.requireText(value, "evidenceReference");
    }
  }

  public record IdempotencyKey(String value) {
    public IdempotencyKey {
      Validation.requireText(value, "idempotencyKey");
    }
  }

  public record ChildWorkflowId(String value) {
    public ChildWorkflowId {
      Validation.requireText(value, "childWorkflowId");
    }
  }

  public record AuthorizationId(String value) {
    public AuthorizationId {
      Validation.requireText(value, "authorizationId");
    }
  }

  public record PolicyRef(String value) {
    public PolicyRef {
      Validation.requireText(value, "policyRef");
    }
  }

  public record TeamOrRoleRef(String value) {
    public TeamOrRoleRef {
      Validation.requireText(value, "teamOrRoleRef");
    }
  }

  public enum ClearanceBranch {
    HANDOVER,
    FINANCE,
    LEGAL
  }

  public enum ClearanceOutcome {
    GREEN,
    AMBER,
    RED
  }

  public enum BranchStatus {
    OPEN,
    CLAIMED,
    COMPLETED
  }

  public enum RequestStatus {
    SUBMITTED,
    WAITING_FOR_INSPECTION,
    CLEARANCE_IN_PROGRESS,
    AUTHORIZED,
    HOLD,
    EXCEPTION_APPROVAL_REQUIRED,
    NOTIFICATION_FAILED
  }

  public enum AssignmentMode {
    MANUAL,
    AUTOMATIC
  }

  public enum FinalAction {
    AUTHORIZE_RELEASE,
    HOLD,
    EXCEPTION_APPROVAL_REQUIRED
  }

  public enum Permission {
    VIEW_TASK,
    CLAIM_TASK,
    COMPLETE_TASK,
    REASSIGN_TASK,
    EMERGENCY_REASSIGN
  }

  public record Actor(ActorId actorId, Set<Permission> permissions, Set<String> authorityScopes) {
    public Actor {
      Validation.requirePresent(actorId, "actorId");
      permissions = Set.copyOf(Validation.requirePresent(permissions, "permissions"));
      authorityScopes = Set.copyOf(Validation.requirePresent(authorityScopes, "authorityScopes"));
    }

    public boolean can(Permission permission) {
      return permissions.contains(permission);
    }
  }

  public record HumanTaskPolicy(
      TeamOrRoleRef eligibleTeamOrRole,
      AssignmentMode assignmentMode,
      PolicyRef assignmentPolicyRef,
      PolicyRef slaPolicyRef,
      PolicyRef escalationPolicyRef,
      int taskWeight,
      Set<String> requiredAuthorityScopes) {
    public HumanTaskPolicy {
      Validation.requirePresent(eligibleTeamOrRole, "eligibleTeamOrRole");
      Validation.requirePresent(assignmentMode, "assignmentMode");
      Validation.requirePresent(assignmentPolicyRef, "assignmentPolicyRef");
      Validation.requirePresent(slaPolicyRef, "slaPolicyRef");
      Validation.requirePresent(escalationPolicyRef, "escalationPolicyRef");
      Validation.requirePositive(taskWeight, "taskWeight");
      requiredAuthorityScopes =
          Set.copyOf(Validation.requirePresent(requiredAuthorityScopes, "requiredAuthorityScopes"));
    }
  }

  public record SlicePolicies(
      Map<ClearanceBranch, HumanTaskPolicy> taskPolicies,
      PolicyRef decisionPolicyVersion,
      int maxConnectorAttempts,
      Duration retryBackoff) {
    public SlicePolicies {
      taskPolicies = Map.copyOf(Validation.requirePresent(taskPolicies, "taskPolicies"));
      for (ClearanceBranch branch : ClearanceBranch.values()) {
        if (!taskPolicies.containsKey(branch)) {
          throw new IllegalArgumentException("task policy missing for " + branch);
        }
      }
      Validation.requirePresent(decisionPolicyVersion, "decisionPolicyVersion");
      Validation.requirePositive(maxConnectorAttempts, "maxConnectorAttempts");
      retryBackoff = Validation.requirePresent(retryBackoff, "retryBackoff");
      if (retryBackoff.isNegative()) {
        throw new IllegalArgumentException("retryBackoff must not be negative");
      }
    }
  }

  public record KeyHandoverSubmission(
      BusinessKey businessKey,
      PropertyReference propertyReference,
      OwnerReference ownerReference,
      Actor submittedBy,
      CorrelationId correlationId,
      CausationId causationId) {
    public KeyHandoverSubmission {
      Validation.requirePresent(businessKey, "businessKey");
      Validation.requirePresent(propertyReference, "propertyReference");
      Validation.requirePresent(ownerReference, "ownerReference");
      Validation.requirePresent(submittedBy, "submittedBy");
      Validation.requirePresent(correlationId, "correlationId");
      Validation.requirePresent(causationId, "causationId");
    }
  }

  public record InspectionStatus(
      boolean validInspectionExists, Optional<ChildWorkflowId> existingChildWorkflowId) {
    public InspectionStatus {
      existingChildWorkflowId =
          existingChildWorkflowId == null ? Optional.empty() : existingChildWorkflowId;
    }
  }

  public record FinanceClearance(
      BigDecimal outstandingAmount, ClearanceOutcome outcome, EvidenceReference evidence) {
    public FinanceClearance {
      Validation.requirePresent(outstandingAmount, "outstandingAmount");
      Validation.requirePresent(outcome, "outcome");
      Validation.requirePresent(evidence, "evidence");
    }
  }

  public record BranchCompletion(
      KeyHandoverRequestId requestId,
      ClearanceBranch branch,
      ClearanceOutcome outcome,
      List<EvidenceReference> evidenceReferences,
      Actor completedBy,
      DomainVersion expectedStateVersion,
      CorrelationId correlationId,
      CausationId causationId) {
    public BranchCompletion {
      Validation.requirePresent(requestId, "requestId");
      Validation.requirePresent(branch, "branch");
      Validation.requirePresent(outcome, "outcome");
      evidenceReferences = Validation.requireNonEmptyList(evidenceReferences, "evidenceReferences");
      Validation.requirePresent(completedBy, "completedBy");
      Validation.requirePresent(expectedStateVersion, "expectedStateVersion");
      Validation.requirePresent(correlationId, "correlationId");
      Validation.requirePresent(causationId, "causationId");
    }
  }

  public record BranchState(
      ClearanceBranch branch,
      BranchStatus status,
      HumanTaskPolicy taskPolicy,
      Optional<ActorId> assignedTo,
      Optional<ActorId> completedBy,
      Optional<ClearanceOutcome> outcome,
      List<EvidenceReference> evidenceReferences,
      Instant openedAt,
      Optional<Instant> slaWarningAt,
      Optional<Instant> slaBreachedAt) {
    public BranchState {
      Validation.requirePresent(branch, "branch");
      Validation.requirePresent(status, "status");
      Validation.requirePresent(taskPolicy, "taskPolicy");
      assignedTo = assignedTo == null ? Optional.empty() : assignedTo;
      completedBy = completedBy == null ? Optional.empty() : completedBy;
      outcome = outcome == null ? Optional.empty() : outcome;
      evidenceReferences = Validation.requireList(evidenceReferences, "evidenceReferences");
      Validation.requirePresent(openedAt, "openedAt");
      slaWarningAt = slaWarningAt == null ? Optional.empty() : slaWarningAt;
      slaBreachedAt = slaBreachedAt == null ? Optional.empty() : slaBreachedAt;
    }

    public BranchState claimedBy(ActorId actorId) {
      return new BranchState(
          branch,
          BranchStatus.CLAIMED,
          taskPolicy,
          Optional.of(actorId),
          completedBy,
          outcome,
          evidenceReferences,
          openedAt,
          slaWarningAt,
          slaBreachedAt);
    }

    public BranchState reassignedTo(ActorId actorId) {
      return new BranchState(
          branch,
          status,
          taskPolicy,
          Optional.of(actorId),
          completedBy,
          outcome,
          evidenceReferences,
          openedAt,
          slaWarningAt,
          slaBreachedAt);
    }

    public BranchState completed(
        ClearanceOutcome newOutcome, List<EvidenceReference> evidence, ActorId actorId) {
      return new BranchState(
          branch,
          BranchStatus.COMPLETED,
          taskPolicy,
          assignedTo,
          Optional.of(actorId),
          Optional.of(newOutcome),
          evidence,
          openedAt,
          slaWarningAt,
          slaBreachedAt);
    }

    public BranchState withSla(Optional<Instant> warningAt, Optional<Instant> breachedAt) {
      return new BranchState(
          branch,
          status,
          taskPolicy,
          assignedTo,
          completedBy,
          outcome,
          evidenceReferences,
          openedAt,
          warningAt,
          breachedAt);
    }
  }

  public record FinalDecision(
      FinalAction action,
      Map<ClearanceBranch, ClearanceOutcome> combinedOutcomes,
      PolicyRef policyVersion,
      List<EvidenceReference> evidenceReferences,
      CorrelationId correlationId,
      CausationId causationId) {
    public FinalDecision {
      Validation.requirePresent(action, "action");
      combinedOutcomes =
          Map.copyOf(Validation.requirePresent(combinedOutcomes, "combinedOutcomes"));
      Validation.requirePresent(policyVersion, "policyVersion");
      evidenceReferences = Validation.requireList(evidenceReferences, "evidenceReferences");
      Validation.requirePresent(correlationId, "correlationId");
      Validation.requirePresent(causationId, "causationId");
    }
  }

  public record KeyReleaseAuthorization(
      AuthorizationId authorizationId,
      KeyHandoverRequestId requestId,
      List<EvidenceReference> evidenceReferences,
      Instant authorizedAt) {
    public KeyReleaseAuthorization {
      Validation.requirePresent(authorizationId, "authorizationId");
      Validation.requirePresent(requestId, "requestId");
      evidenceReferences = Validation.requireList(evidenceReferences, "evidenceReferences");
      Validation.requirePresent(authorizedAt, "authorizedAt");
    }
  }

  public record AuditRecord(
      String eventType,
      KeyHandoverRequestId requestId,
      DomainVersion stateVersion,
      CorrelationId correlationId,
      CausationId causationId,
      ActorId actorId,
      Instant occurredAt,
      List<EvidenceReference> evidenceReferences) {
    public AuditRecord {
      eventType = Validation.requireText(eventType, "eventType");
      Validation.requirePresent(requestId, "requestId");
      Validation.requirePresent(stateVersion, "stateVersion");
      Validation.requirePresent(correlationId, "correlationId");
      Validation.requirePresent(causationId, "causationId");
      Validation.requirePresent(actorId, "actorId");
      Validation.requirePresent(occurredAt, "occurredAt");
      evidenceReferences = Validation.requireList(evidenceReferences, "evidenceReferences");
    }
  }

  public record EmergencyReassignment(
      KeyHandoverRequestId requestId,
      ClearanceBranch branch,
      Actor teamHead,
      ActorId newAssignee,
      String reason,
      Instant expiresAt,
      DomainVersion expectedStateVersion,
      CorrelationId correlationId,
      CausationId causationId) {
    public EmergencyReassignment {
      Validation.requirePresent(requestId, "requestId");
      Validation.requirePresent(branch, "branch");
      Validation.requirePresent(teamHead, "teamHead");
      Validation.requirePresent(newAssignee, "newAssignee");
      reason = Validation.requireText(reason, "reason");
      Validation.requirePresent(expiresAt, "expiresAt");
      Validation.requirePresent(expectedStateVersion, "expectedStateVersion");
      Validation.requirePresent(correlationId, "correlationId");
      Validation.requirePresent(causationId, "causationId");
    }
  }
}
