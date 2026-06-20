package com.sevenewf.workflow.domain.keyhandover;

import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.*;
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
    Optional<ExceptionDecision> exceptionDecision,
    Optional<KeyReleaseAuthorization> authorization,
    Optional<NotificationState> notificationState,
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
    exceptionDecision = exceptionDecision == null ? Optional.empty() : exceptionDecision;
    authorization = authorization == null ? Optional.empty() : authorization;
    notificationState = notificationState == null ? Optional.empty() : notificationState;
    Validation.requirePresent(updatedAt, "updatedAt");
  }

  public KeyHandoverState next(
      RequestStatus newStatus,
      InspectionStatus newInspectionStatus,
      Optional<ChildWorkflowId> newInspectionChildWorkflowId,
      Map<ClearanceBranch, BranchState> newBranches,
      Optional<FinalDecision> newFinalDecision,
      Optional<ExceptionDecision> newExceptionDecision,
      Optional<KeyReleaseAuthorization> newAuthorization,
      Optional<NotificationState> newNotificationState,
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
        newExceptionDecision,
        newAuthorization,
        newNotificationState,
        now);
  }
}
