package com.sevenewf.workflow.domain.keyhandover.hold;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ClearanceBranch;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverRequestId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PolicyRef;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

public record HoldRecord(
    HoldId holdId,
    KeyHandoverRequestId requestId,
    int cycleNumber,
    PolicyRef policyReference,
    HoldPolicy policy,
    HoldLifecycleStatus status,
    String reason,
    Set<ClearanceBranch> affectedBranches,
    ActorId owner,
    ActorId createdBy,
    Instant startedAt,
    Instant reviewAt,
    Instant expiresAt,
    int extensionCount,
    Map<ClearanceBranch, BranchRemediation> remediationByBranch,
    DomainVersion stateVersion,
    CorrelationId correlationId,
    CausationId causationId) {
  public HoldRecord {
    Validation.requirePresent(holdId, "holdId");
    Validation.requirePresent(requestId, "requestId");
    Validation.requirePositive(cycleNumber, "cycleNumber");
    Validation.requirePresent(policyReference, "policyReference");
    Validation.requirePresent(policy, "policy");
    if (!policy.policyReference().equals(policyReference)) {
      throw new IllegalArgumentException("policy reference does not match hold policy");
    }
    Validation.requirePresent(status, "status");
    reason = Validation.requireText(reason, "reason");
    affectedBranches = Set.copyOf(Validation.requirePresent(affectedBranches, "affectedBranches"));
    if (affectedBranches.isEmpty()) {
      throw new IllegalArgumentException("affectedBranches must not be empty");
    }
    Validation.requirePresent(owner, "owner");
    Validation.requirePresent(createdBy, "createdBy");
    Validation.requirePresent(startedAt, "startedAt");
    Validation.requirePresent(reviewAt, "reviewAt");
    Validation.requirePresent(expiresAt, "expiresAt");
    if (!reviewAt.isAfter(startedAt)) {
      throw new IllegalArgumentException("reviewAt must be after startedAt");
    }
    if (!expiresAt.isAfter(reviewAt)) {
      throw new IllegalArgumentException("expiresAt must be after reviewAt");
    }
    if (extensionCount < 0) {
      throw new IllegalArgumentException("extensionCount must not be negative");
    }
    HoldTiming.validateExtensionCount(extensionCount, policy);
    remediationByBranch =
        Map.copyOf(Validation.requirePresent(remediationByBranch, "remediationByBranch"));
    if (!affectedBranches.containsAll(remediationByBranch.keySet())) {
      throw new IllegalArgumentException("remediation branches must be affected branches");
    }
    Validation.requirePresent(stateVersion, "stateVersion");
    Validation.requirePresent(correlationId, "correlationId");
    Validation.requirePresent(causationId, "causationId");
  }
}
