package com.sevenewf.workflow.domain.keyhandover.hold;

import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ClearanceBranch;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PolicyRef;
import java.util.Set;

public interface HoldPolicy {
  PolicyRef policyReference();

  BusinessDays reviewPeriod();

  BusinessDays maximumInitialHoldDuration();

  int maximumExtensionCount();

  BusinessDays maximumExtensionDuration();

  Set<HoldRole> eligibleHoldOwnerRoles();

  Set<HoldRole> eligibleHoldManagerRoles();

  HoldVisibility visibilityFor(HoldRole role, ClearanceBranch branch, boolean ownsBranch);

  boolean allowsResume(HoldLifecycleStatus status);

  boolean requiresEscalationWhenReviewDue();

  boolean allowsExpiryOutcome(HoldLifecycleStatus status);
}
