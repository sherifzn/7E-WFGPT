package com.sevenewf.workflow.domain.keyhandover.hold;

import com.sevenewf.workflow.domain.common.Validation;
import java.time.Instant;

public final class HoldTiming {
  private HoldTiming() {}

  public static Instant calculateReviewAt(
      Instant startedAt, HoldPolicy policy, BusinessCalendar calendar) {
    Validation.requirePresent(policy, "policy");
    return calendar.calculateFrom(startedAt, policy.reviewPeriod());
  }

  public static Instant calculateInitialExpiryAt(
      Instant startedAt, HoldPolicy policy, BusinessCalendar calendar) {
    Validation.requirePresent(policy, "policy");
    return calendar.calculateFrom(startedAt, policy.maximumInitialHoldDuration());
  }

  public static void validateExtensionCount(int extensionCount, HoldPolicy policy) {
    Validation.requirePresent(policy, "policy");
    if (extensionCount < 0 || extensionCount > policy.maximumExtensionCount()) {
      throw new IllegalArgumentException("extensionCount is outside the policy limit");
    }
  }

  public static Instant calculateExtendedExpiryAt(
      Instant expiresAt,
      BusinessDays extensionDuration,
      HoldPolicy policy,
      BusinessCalendar calendar) {
    Validation.requirePresent(expiresAt, "expiresAt");
    Validation.requirePresent(extensionDuration, "extensionDuration");
    Validation.requirePresent(policy, "policy");
    if (extensionDuration.exceeds(policy.maximumExtensionDuration())) {
      throw new IllegalArgumentException("extensionDuration exceeds the policy maximum");
    }
    return calendar.calculateFrom(expiresAt, extensionDuration);
  }

  public static boolean isReviewDue(Instant now, HoldRecord hold) {
    Validation.requirePresent(now, "now");
    Validation.requirePresent(hold, "hold");
    return !now.isBefore(hold.reviewAt()) && now.isBefore(hold.expiresAt());
  }

  public static boolean isExpired(Instant now, HoldRecord hold) {
    Validation.requirePresent(now, "now");
    Validation.requirePresent(hold, "hold");
    return !now.isBefore(hold.expiresAt());
  }
}
