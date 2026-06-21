package com.sevenewf.workflow.domain.keyhandover.hold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ClearanceBranch;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EvidenceReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverRequestId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class HoldManagementDomainTest {
  private static final Instant STARTED_AT = Instant.parse("2026-01-05T09:00:00Z");
  private static final LocalKeyHandoverHoldPolicy POLICY = new LocalKeyHandoverHoldPolicy();
  private static final BusinessCalendar CALENDAR =
      (start, businessDays) -> start.plus(businessDays.value(), ChronoUnit.DAYS);

  @Test
  void constructsAcceptedLocalPolicy() {
    assertEquals("key-handover-hold-policy-v1-local", POLICY.policyReference().value());
    assertEquals(new BusinessDays(2), POLICY.reviewPeriod());
    assertEquals(new BusinessDays(10), POLICY.maximumInitialHoldDuration());
    assertEquals(2, POLICY.maximumExtensionCount());
    assertEquals(new BusinessDays(5), POLICY.maximumExtensionDuration());
  }

  @Test
  void rejectsZeroOrNegativeBusinessDays() {
    assertThrows(IllegalArgumentException.class, () -> new BusinessDays(0));
    assertThrows(IllegalArgumentException.class, () -> new BusinessDays(-1));
  }

  @Test
  void constructsValidHoldRecord() {
    HoldRecord hold = hold(0, Set.of(ClearanceBranch.FINANCE), Map.of());

    assertEquals(HoldLifecycleStatus.ACTIVE, hold.status());
    assertEquals(1, hold.cycleNumber());
  }

  @Test
  void rejectsEmptyAffectedBranches() {
    assertThrows(IllegalArgumentException.class, () -> hold(0, Set.of(), Map.of()));
  }

  @Test
  void rejectsNullAffectedBranch() {
    assertThrows(
        NullPointerException.class, () -> hold(0, java.util.Collections.singleton(null), Map.of()));
  }

  @Test
  void rejectsReviewAtOrBeforeStartedAt() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            hold(0, Set.of(ClearanceBranch.FINANCE), Map.of(), STARTED_AT, STARTED_AT, expiryAt()));
  }

  @Test
  void rejectsExpiryAtOrBeforeReviewAt() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            hold(0, Set.of(ClearanceBranch.FINANCE), Map.of(), STARTED_AT, reviewAt(), reviewAt()));
  }

  @Test
  void rejectsRemediationForUnaffectedBranch() {
    BranchRemediation remediation = remediation(ClearanceBranch.LEGAL);

    assertThrows(
        IllegalArgumentException.class,
        () -> hold(0, Set.of(ClearanceBranch.FINANCE), Map.of(ClearanceBranch.LEGAL, remediation)));
  }

  @Test
  void rejectsNegativeExtensionCount() {
    assertThrows(
        IllegalArgumentException.class, () -> hold(-1, Set.of(ClearanceBranch.FINANCE), Map.of()));
  }

  @Test
  void rejectsExtensionCountAbovePolicyMaximum() {
    assertThrows(
        IllegalArgumentException.class, () -> hold(3, Set.of(ClearanceBranch.FINANCE), Map.of()));
  }

  @Test
  void constructsValidBranchRemediation() {
    assertEquals(ClearanceBranch.FINANCE, remediation(ClearanceBranch.FINANCE).branch());
  }

  @Test
  void rejectsBlankRemediationSummary() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BranchRemediation(
                ClearanceBranch.FINANCE,
                " ",
                new EvidenceReference("evidence-001"),
                new ActorId("owner-001"),
                STARTED_AT));
  }

  @Test
  void rejectsBlankSupportingReference() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BranchRemediation(
                ClearanceBranch.FINANCE,
                "Resolved",
                new EvidenceReference(" "),
                new ActorId("owner-001"),
                STARTED_AT));
  }

  @Test
  void calculatesReviewAndInitialExpiryDates() {
    assertEquals(
        STARTED_AT.plus(2, ChronoUnit.DAYS),
        HoldTiming.calculateReviewAt(STARTED_AT, POLICY, CALENDAR));
    assertEquals(
        STARTED_AT.plus(10, ChronoUnit.DAYS),
        HoldTiming.calculateInitialExpiryAt(STARTED_AT, POLICY, CALENDAR));
  }

  @Test
  void calculatesExtendedExpiryAndValidatesExtensionLimits() {
    assertEquals(
        expiryAt().plus(5, ChronoUnit.DAYS),
        HoldTiming.calculateExtendedExpiryAt(expiryAt(), new BusinessDays(5), POLICY, CALENDAR));
    HoldTiming.validateExtensionCount(2, POLICY);
    assertThrows(
        IllegalArgumentException.class, () -> HoldTiming.validateExtensionCount(3, POLICY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HoldTiming.calculateExtendedExpiryAt(
                expiryAt(), new BusinessDays(6), POLICY, CALENDAR));
  }

  @Test
  void detectsReviewDueAndExpiry() {
    HoldRecord hold = hold(0, Set.of(ClearanceBranch.FINANCE), Map.of());

    assertTrue(HoldTiming.isReviewDue(reviewAt(), hold));
    assertFalse(HoldTiming.isReviewDue(expiryAt(), hold));
    assertFalse(HoldTiming.isExpired(reviewAt(), hold));
    assertTrue(HoldTiming.isExpired(expiryAt(), hold));
  }

  @Test
  void prohibitsDirectResumeFromExpiredHold() {
    assertFalse(POLICY.allowsResume(HoldLifecycleStatus.EXPIRED));
  }

  @Test
  void processOwnerHasFullHoldManagementVisibility() {
    assertEquals(
        HoldVisibility.FULL_MANAGEMENT,
        POLICY.visibilityFor(HoldRole.PROCESS_OWNER, ClearanceBranch.FINANCE, false));
  }

  @Test
  void teamHeadHasReadOnlyHoldVisibility() {
    assertEquals(
        HoldVisibility.HOLD_READ_ONLY,
        POLICY.visibilityFor(HoldRole.TEAM_HEAD, ClearanceBranch.FINANCE, false));
  }

  @Test
  void branchOfficerCanSeeOnlyOwnBranchRemediation() {
    assertEquals(
        HoldVisibility.REMEDIATION_READ,
        POLICY.visibilityFor(HoldRole.BRANCH_OFFICER, ClearanceBranch.FINANCE, true));
    assertEquals(
        HoldVisibility.NO_ACCESS,
        POLICY.visibilityFor(HoldRole.BRANCH_OFFICER, ClearanceBranch.FINANCE, false));
  }

  private static HoldRecord hold(
      int extensionCount,
      Set<ClearanceBranch> affectedBranches,
      Map<ClearanceBranch, BranchRemediation> remediationByBranch) {
    return hold(
        extensionCount, affectedBranches, remediationByBranch, STARTED_AT, reviewAt(), expiryAt());
  }

  private static HoldRecord hold(
      int extensionCount,
      Set<ClearanceBranch> affectedBranches,
      Map<ClearanceBranch, BranchRemediation> remediationByBranch,
      Instant startedAt,
      Instant reviewAt,
      Instant expiresAt) {
    return new HoldRecord(
        new HoldId("hold-001"),
        new KeyHandoverRequestId("request-001"),
        1,
        POLICY.policyReference(),
        POLICY,
        HoldLifecycleStatus.ACTIVE,
        "Outstanding clearance issue",
        affectedBranches,
        new ActorId("owner-001"),
        new ActorId("creator-001"),
        startedAt,
        reviewAt,
        expiresAt,
        extensionCount,
        remediationByBranch,
        new DomainVersion(1),
        new CorrelationId("correlation-001"),
        new CausationId("causation-001"));
  }

  private static BranchRemediation remediation(ClearanceBranch branch) {
    return new BranchRemediation(
        branch,
        "Clearance evidence recorded",
        new EvidenceReference("evidence-001"),
        new ActorId("officer-001"),
        STARTED_AT);
  }

  private static Instant reviewAt() {
    return STARTED_AT.plus(2, ChronoUnit.DAYS);
  }

  private static Instant expiryAt() {
    return STARTED_AT.plus(10, ChronoUnit.DAYS);
  }
}
