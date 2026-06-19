package com.sevenewf.workflow.domain.keyhandover;

import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.AuthorizationId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.BranchState;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.BusinessKey;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ChildWorkflowId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ClearanceBranch;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.FinalDecision;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.InspectionStatus;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverRequestId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyReleaseAuthorization;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.OwnerReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PropertyReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.RequestStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

public record KeyHandoverState(
    KeyHandoverRequestId requestId,
    DomainVersion stateVersion,
    BusinessKey businessKey,
    PropertyReference propertyReference,
    OwnerReference ownerReference,
    RequestStatus status,
    InspectionStatus inspectionStatus,
    Optional<ChildWorkflowId> inspectionChildWorkflowId,
    Map<ClearanceBranch, BranchState> branches,
    Optional<FinalDecision> finalDecision,
    Optional<KeyReleaseAuthorization> authorization,
    Optional<AuthorizationId> notificationIdempotencyKey,
    Instant updatedAt) {
  public KeyHandoverState {
    Validation.requirePresent(requestId, "requestId");
    Validation.requirePresent(stateVersion, "stateVersion");
    Validation.requirePresent(businessKey, "businessKey");
    Validation.requirePresent(propertyReference, "propertyReference");
    Validation.requirePresent(ownerReference, "ownerReference");
    Validation.requirePresent(status, "status");
    Validation.requirePresent(inspectionStatus, "inspectionStatus");
    inspectionChildWorkflowId =
        inspectionChildWorkflowId == null ? Optional.empty() : inspectionChildWorkflowId;
    branches = Map.copyOf(Validation.requirePresent(branches, "branches"));
    finalDecision = finalDecision == null ? Optional.empty() : finalDecision;
    authorization = authorization == null ? Optional.empty() : authorization;
    notificationIdempotencyKey =
        notificationIdempotencyKey == null ? Optional.empty() : notificationIdempotencyKey;
    Validation.requirePresent(updatedAt, "updatedAt");
  }

  public KeyHandoverState next(
      RequestStatus newStatus,
      InspectionStatus newInspectionStatus,
      Optional<ChildWorkflowId> newInspectionChildWorkflowId,
      Map<ClearanceBranch, BranchState> newBranches,
      Optional<FinalDecision> newFinalDecision,
      Optional<KeyReleaseAuthorization> newAuthorization,
      Optional<AuthorizationId> newNotificationIdempotencyKey,
      Instant now) {
    return new KeyHandoverState(
        requestId,
        new DomainVersion(stateVersion.value() + 1),
        businessKey,
        propertyReference,
        ownerReference,
        newStatus,
        newInspectionStatus,
        newInspectionChildWorkflowId,
        newBranches,
        newFinalDecision,
        newAuthorization,
        newNotificationIdempotencyKey,
        now);
  }
}
