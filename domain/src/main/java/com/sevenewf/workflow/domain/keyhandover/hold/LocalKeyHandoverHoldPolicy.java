package com.sevenewf.workflow.domain.keyhandover.hold;

import com.sevenewf.workflow.domain.common.Validation;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ClearanceBranch;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PolicyRef;
import java.util.Set;

public final class LocalKeyHandoverHoldPolicy implements HoldPolicy {
  public static final PolicyRef POLICY_REFERENCE =
      new PolicyRef("key-handover-hold-policy-v1-local");

  @Override
  public PolicyRef policyReference() {
    return POLICY_REFERENCE;
  }

  @Override
  public BusinessDays reviewPeriod() {
    return new BusinessDays(2);
  }

  @Override
  public BusinessDays maximumInitialHoldDuration() {
    return new BusinessDays(10);
  }

  @Override
  public int maximumExtensionCount() {
    return 2;
  }

  @Override
  public BusinessDays maximumExtensionDuration() {
    return new BusinessDays(5);
  }

  @Override
  public Set<HoldRole> eligibleHoldOwnerRoles() {
    return Set.of(HoldRole.PROCESS_OWNER);
  }

  @Override
  public Set<HoldRole> eligibleHoldManagerRoles() {
    return Set.of(HoldRole.PROCESS_OWNER);
  }

  @Override
  public HoldVisibility visibilityFor(HoldRole role, ClearanceBranch branch, boolean ownsBranch) {
    Validation.requirePresent(role, "role");
    Validation.requirePresent(branch, "branch");
    return switch (role) {
      case PROCESS_OWNER -> HoldVisibility.FULL_MANAGEMENT;
      case TEAM_HEAD -> HoldVisibility.HOLD_READ_ONLY;
      case BRANCH_OFFICER ->
          ownsBranch ? HoldVisibility.REMEDIATION_READ : HoldVisibility.NO_ACCESS;
      case REQUESTER -> HoldVisibility.NO_ACCESS;
    };
  }

  @Override
  public boolean allowsResume(HoldLifecycleStatus status) {
    Validation.requirePresent(status, "status");
    return status != HoldLifecycleStatus.EXPIRED;
  }

  @Override
  public boolean requiresEscalationWhenReviewDue() {
    return true;
  }

  @Override
  public boolean allowsExpiryOutcome(HoldLifecycleStatus status) {
    Validation.requirePresent(status, "status");
    return status == HoldLifecycleStatus.EXTENDED
        || status == HoldLifecycleStatus.REJECTED
        || status == HoldLifecycleStatus.CANCELLED;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof LocalKeyHandoverHoldPolicy;
  }

  @Override
  public int hashCode() {
    return LocalKeyHandoverHoldPolicy.class.hashCode();
  }
}
