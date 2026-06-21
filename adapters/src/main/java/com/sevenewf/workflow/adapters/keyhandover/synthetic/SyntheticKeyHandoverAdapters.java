package com.sevenewf.workflow.adapters.keyhandover.synthetic;

import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.*;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.*;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverState;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.*;
import com.sevenewf.workflow.domain.keyhandover.hold.*;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class SyntheticKeyHandoverAdapters {
  private SyntheticKeyHandoverAdapters() {}

  public static final class ControllableClock implements Clock {
    private Instant now;

    public ControllableClock(Instant now) {
      this.now = now;
    }

    public Instant now() {
      return now;
    }

    public void setNow(Instant now) {
      this.now = now;
    }
  }

  public static final class RecordingRetryScheduler implements RetryScheduler {
    private final List<Duration> requestedBackoffs = new ArrayList<>();

    public void backoff(Duration delay, int failedAttempt, String operation) {
      requestedBackoffs.add(delay);
    }

    public List<Duration> requestedBackoffs() {
      return List.copyOf(requestedBackoffs);
    }
  }

  public static final class FixedAutomaticAssignmentService implements AutomaticAssignmentService {
    private final Actor assignee;

    public FixedAutomaticAssignmentService(Actor assignee) {
      this.assignee = assignee;
    }

    public Actor assign(HumanTaskPolicy taskPolicy) {
      return assignee;
    }
  }

  public static class InMemoryKeyHandoverStateStore implements KeyHandoverStateStore {
    protected final Map<KeyHandoverRequestId, KeyHandoverState> byId = new HashMap<>();
    protected final Map<BusinessKey, KeyHandoverRequestId> byBusinessKey = new HashMap<>();
    protected final List<AuditRecord> pending = new ArrayList<>();

    public Optional<KeyHandoverState> findByBusinessKey(BusinessKey key) {
      return Optional.ofNullable(byBusinessKey.get(key)).flatMap(this::findById);
    }

    public Optional<KeyHandoverState> findById(KeyHandoverRequestId id) {
      return Optional.ofNullable(byId.get(id));
    }

    public synchronized KeyHandoverState insertIfAbsent(
        KeyHandoverState state, List<AuditRecord> audits) {
      if (byBusinessKey.containsKey(state.businessKey()))
        return byId.get(byBusinessKey.get(state.businessKey()));
      byId.put(state.requestId(), state);
      byBusinessKey.put(state.businessKey(), state.requestId());
      pending.addAll(audits);
      afterMutation();
      return state;
    }

    public synchronized KeyHandoverState commit(
        KeyHandoverState state, DomainVersion expected, List<AuditRecord> audits) {
      KeyHandoverState current = byId.get(state.requestId());
      if (current == null || !current.stateVersion().equals(expected))
        throw new OptimisticStateConflictException("State version conflict");
      byId.put(state.requestId(), state);
      byBusinessKey.put(state.businessKey(), state.requestId());
      pending.addAll(audits);
      afterMutation();
      return state;
    }

    public synchronized void appendPendingAudit(AuditRecord audit) {
      pending.add(audit);
      afterMutation();
    }

    public synchronized List<AuditRecord> pendingAudits() {
      return List.copyOf(pending);
    }

    public synchronized void markAuditDelivered(AuditRecord audit) {
      pending.remove(audit);
      afterMutation();
    }

    protected void afterMutation() {}
  }

  /**
   * File-backed synthetic state store for local development and tests; production persistence
   * remains an ADR.
   */
  public static class PathBackedKeyHandoverStateStore extends InMemoryKeyHandoverStateStore {
    private final Path location;

    public PathBackedKeyHandoverStateStore(Path location) {
      this.location = location.toAbsolutePath().normalize();
      load();
    }

    public synchronized List<KeyHandoverState> states() {
      return byId.values().stream()
          .sorted(Comparator.comparing(KeyHandoverState::updatedAt).reversed())
          .toList();
    }

    @Override
    protected void afterMutation() {
      Path temporary = location.resolveSibling(location.getFileName() + ".tmp");
      try {
        Path parent = location.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (OutputStream output = Files.newOutputStream(temporary);
            DataOutputStream data = new DataOutputStream(output)) {
          StateFileCodec.write(data, byId, byBusinessKey, pending);
        }
        try {
          Files.move(
              temporary,
              location,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
          Files.move(temporary, location, StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (IOException exception) {
        throw new IllegalStateException("Unable to persist local key handover snapshot", exception);
      }
    }

    private void load() {
      try {
        if (!Files.exists(location) || Files.size(location) == 0) return;
        try (InputStream input = Files.newInputStream(location);
            DataInputStream data = new DataInputStream(input)) {
          StateFileCodec.read(data, byId, byBusinessKey, pending);
        }
      } catch (IOException exception) {
        throw new IllegalStateException("Unable to load local key handover snapshot", exception);
      }
    }
  }

  /** Compatibility name retained for Task 003 tests. */
  public static final class TestOnlyPathBackedKeyHandoverStateStore
      extends PathBackedKeyHandoverStateStore {
    public TestOnlyPathBackedKeyHandoverStateStore(Path location) {
      super(location);
    }
  }

  private static final class StateFileCodec {
    private static final int FORMAT_VERSION = 3;

    private StateFileCodec() {}

    static void write(
        DataOutputStream data,
        Map<KeyHandoverRequestId, KeyHandoverState> byId,
        Map<BusinessKey, KeyHandoverRequestId> byBusinessKey,
        List<AuditRecord> pending)
        throws IOException {
      data.writeInt(FORMAT_VERSION);
      data.writeInt(byId.size());
      for (KeyHandoverState state : byId.values()) writeState(data, state);
      data.writeInt(byBusinessKey.size());
      for (Map.Entry<BusinessKey, KeyHandoverRequestId> entry : byBusinessKey.entrySet()) {
        data.writeUTF(entry.getKey().value());
        data.writeUTF(entry.getValue().value());
      }
      data.writeInt(pending.size());
      for (AuditRecord audit : pending) writeAudit(data, audit);
    }

    static void read(
        DataInputStream data,
        Map<KeyHandoverRequestId, KeyHandoverState> byId,
        Map<BusinessKey, KeyHandoverRequestId> byBusinessKey,
        List<AuditRecord> pending)
        throws IOException {
      int formatVersion = data.readInt();
      if (formatVersion < 1 || formatVersion > FORMAT_VERSION)
        throw new IOException("Unsupported test snapshot format");
      int stateCount = data.readInt();
      for (int index = 0; index < stateCount; index++) {
        KeyHandoverState state = readState(data, formatVersion);
        byId.put(state.requestId(), state);
      }
      int indexCount = data.readInt();
      for (int index = 0; index < indexCount; index++)
        byBusinessKey.put(
            new BusinessKey(data.readUTF()), new KeyHandoverRequestId(data.readUTF()));
      int auditCount = data.readInt();
      for (int index = 0; index < auditCount; index++) pending.add(readAudit(data));
    }

    private static void writeState(DataOutputStream data, KeyHandoverState state)
        throws IOException {
      data.writeUTF(state.requestId().value());
      data.writeInt(state.stateVersion().value());
      data.writeUTF(state.businessKey().value());
      data.writeUTF(state.propertyReference().value());
      data.writeUTF(state.ownerReference().value());
      data.writeUTF(state.status().name());
      data.writeBoolean(state.inspectionStatus().validInspectionExists());
      writeOptionalChild(data, state.inspectionStatus().existingChildWorkflowId());
      writeOptionalChild(data, state.inspectionChildWorkflowId());
      data.writeInt(state.branches().size());
      for (BranchState branch : state.branches().values()) writeBranch(data, branch);
      data.writeBoolean(state.finalDecision().isPresent());
      if (state.finalDecision().isPresent())
        writeDecision(data, state.finalDecision().orElseThrow());
      data.writeBoolean(state.exceptionDecision().isPresent());
      if (state.exceptionDecision().isPresent())
        writeExceptionDecision(data, state.exceptionDecision().orElseThrow());
      data.writeBoolean(state.authorization().isPresent());
      if (state.authorization().isPresent())
        writeAuthorization(data, state.authorization().orElseThrow());
      data.writeBoolean(state.notificationState().isPresent());
      if (state.notificationState().isPresent())
        writeNotification(data, state.notificationState().orElseThrow());
      data.writeInt(state.holds().size());
      for (HoldRecord hold : state.holds()) writeHold(data, hold);
      writeInstant(data, state.updatedAt());
    }

    private static KeyHandoverState readState(DataInputStream data, int formatVersion)
        throws IOException {
      KeyHandoverRequestId requestId = new KeyHandoverRequestId(data.readUTF());
      DomainVersion version = new DomainVersion(data.readInt());
      BusinessKey businessKey = new BusinessKey(data.readUTF());
      PropertyReference property = new PropertyReference(data.readUTF());
      OwnerReference owner = new OwnerReference(data.readUTF());
      RequestStatus status = RequestStatus.valueOf(data.readUTF());
      InspectionStatus inspection =
          new InspectionStatus(data.readBoolean(), readOptionalChild(data));
      Optional<ChildWorkflowId> child = readOptionalChild(data);
      int branchCount = data.readInt();
      Map<ClearanceBranch, BranchState> branches = new EnumMap<>(ClearanceBranch.class);
      for (int index = 0; index < branchCount; index++) {
        BranchState branch = readBranch(data);
        branches.put(branch.branch(), branch);
      }
      Optional<FinalDecision> decision =
          data.readBoolean() ? Optional.of(readDecision(data)) : Optional.empty();
      Optional<ExceptionDecision> exceptionDecision =
          formatVersion >= 2 && data.readBoolean()
              ? Optional.of(readExceptionDecision(data))
              : Optional.empty();
      Optional<KeyReleaseAuthorization> authorization =
          data.readBoolean() ? Optional.of(readAuthorization(data)) : Optional.empty();
      Optional<NotificationState> notification =
          data.readBoolean() ? Optional.of(readNotification(data)) : Optional.empty();
      List<HoldRecord> holds = new ArrayList<>();
      if (formatVersion >= 3) {
        int holdCount = data.readInt();
        for (int index = 0; index < holdCount; index++) holds.add(readHold(data));
      }
      return new KeyHandoverState(
          requestId,
          version,
          businessKey,
          property,
          owner,
          status,
          inspection,
          child,
          branches,
          decision,
          exceptionDecision,
          authorization,
          notification,
          holds,
          readInstant(data));
    }

    private static void writeHold(DataOutputStream data, HoldRecord hold) throws IOException {
      data.writeUTF(hold.holdId().value());
      data.writeUTF(hold.requestId().value());
      data.writeInt(hold.cycleNumber());
      data.writeUTF(hold.policyReference().value());
      data.writeUTF(hold.status().name());
      data.writeUTF(hold.reason());
      data.writeInt(hold.affectedBranches().size());
      for (ClearanceBranch branch : hold.affectedBranches()) data.writeUTF(branch.name());
      data.writeUTF(hold.owner().value());
      data.writeUTF(hold.createdBy().value());
      writeInstant(data, hold.startedAt());
      writeInstant(data, hold.reviewAt());
      writeInstant(data, hold.expiresAt());
      data.writeInt(hold.extensionCount());
      data.writeInt(hold.remediationByBranch().size());
      for (BranchRemediation remediation : hold.remediationByBranch().values()) {
        data.writeUTF(remediation.branch().name());
        data.writeUTF(remediation.summary());
        data.writeUTF(remediation.supportingReference().value());
        data.writeUTF(remediation.recordedBy().value());
        writeInstant(data, remediation.recordedAt());
      }
      data.writeInt(hold.stateVersion().value());
      data.writeUTF(hold.correlationId().value());
      data.writeUTF(hold.causationId().value());
    }

    private static HoldRecord readHold(DataInputStream data) throws IOException {
      HoldId holdId = new HoldId(data.readUTF());
      KeyHandoverRequestId requestId = new KeyHandoverRequestId(data.readUTF());
      int cycle = data.readInt();
      PolicyRef policyReference = new PolicyRef(data.readUTF());
      HoldLifecycleStatus status = HoldLifecycleStatus.valueOf(data.readUTF());
      String reason = data.readUTF();
      Set<ClearanceBranch> branches = EnumSet.noneOf(ClearanceBranch.class);
      int branchCount = data.readInt();
      for (int index = 0; index < branchCount; index++)
        branches.add(ClearanceBranch.valueOf(data.readUTF()));
      com.sevenewf.workflow.domain.common.ActorId owner =
          new com.sevenewf.workflow.domain.common.ActorId(data.readUTF());
      com.sevenewf.workflow.domain.common.ActorId createdBy =
          new com.sevenewf.workflow.domain.common.ActorId(data.readUTF());
      Instant startedAt = readInstant(data);
      Instant reviewAt = readInstant(data);
      Instant expiresAt = readInstant(data);
      int extensionCount = data.readInt();
      Map<ClearanceBranch, BranchRemediation> remediations = new EnumMap<>(ClearanceBranch.class);
      int remediationCount = data.readInt();
      for (int index = 0; index < remediationCount; index++) {
        ClearanceBranch branch = ClearanceBranch.valueOf(data.readUTF());
        remediations.put(
            branch,
            new BranchRemediation(
                branch,
                data.readUTF(),
                new EvidenceReference(data.readUTF()),
                new com.sevenewf.workflow.domain.common.ActorId(data.readUTF()),
                readInstant(data)));
      }
      return new HoldRecord(
          holdId,
          requestId,
          cycle,
          policyReference,
          new LocalKeyHandoverHoldPolicy(),
          status,
          reason,
          branches,
          owner,
          createdBy,
          startedAt,
          reviewAt,
          expiresAt,
          extensionCount,
          remediations,
          new DomainVersion(data.readInt()),
          new CorrelationId(data.readUTF()),
          new CausationId(data.readUTF()));
    }

    private static void writeBranch(DataOutputStream data, BranchState branch) throws IOException {
      data.writeUTF(branch.branch().name());
      data.writeUTF(branch.status().name());
      writePolicy(data, branch.taskPolicy());
      writeOptionalActor(data, branch.assignedTo());
      writeOptionalActor(data, branch.completedBy());
      data.writeBoolean(branch.outcome().isPresent());
      if (branch.outcome().isPresent()) data.writeUTF(branch.outcome().orElseThrow().name());
      writeEvidence(data, branch.evidenceReferences());
      writeInstant(data, branch.openedAt());
      writeOptionalInstant(data, branch.slaWarningAt());
      writeOptionalInstant(data, branch.slaBreachedAt());
    }

    private static BranchState readBranch(DataInputStream data) throws IOException {
      ClearanceBranch branch = ClearanceBranch.valueOf(data.readUTF());
      BranchStatus status = BranchStatus.valueOf(data.readUTF());
      HumanTaskPolicy policy = readPolicy(data);
      Optional<com.sevenewf.workflow.domain.common.ActorId> assigned = readOptionalActor(data);
      Optional<com.sevenewf.workflow.domain.common.ActorId> completed = readOptionalActor(data);
      Optional<ClearanceOutcome> outcome =
          data.readBoolean()
              ? Optional.of(ClearanceOutcome.valueOf(data.readUTF()))
              : Optional.empty();
      return new BranchState(
          branch,
          status,
          policy,
          assigned,
          completed,
          outcome,
          readEvidence(data),
          readInstant(data),
          readOptionalInstant(data),
          readOptionalInstant(data));
    }

    private static void writePolicy(DataOutputStream data, HumanTaskPolicy policy)
        throws IOException {
      data.writeUTF(policy.eligibleTeamOrRole().value());
      data.writeUTF(policy.assignmentMode().name());
      data.writeUTF(policy.assignmentPolicyRef().value());
      data.writeUTF(policy.slaPolicyRef().value());
      data.writeUTF(policy.escalationPolicyRef().value());
      data.writeInt(policy.taskWeight());
      writeStrings(data, policy.requiredAuthorityScopes());
    }

    private static HumanTaskPolicy readPolicy(DataInputStream data) throws IOException {
      return new HumanTaskPolicy(
          new TeamOrRoleRef(data.readUTF()),
          AssignmentMode.valueOf(data.readUTF()),
          new PolicyRef(data.readUTF()),
          new PolicyRef(data.readUTF()),
          new PolicyRef(data.readUTF()),
          data.readInt(),
          readStrings(data));
    }

    private static void writeDecision(DataOutputStream data, FinalDecision decision)
        throws IOException {
      data.writeUTF(decision.action().name());
      data.writeInt(decision.combinedOutcomes().size());
      for (Map.Entry<ClearanceBranch, ClearanceOutcome> entry :
          decision.combinedOutcomes().entrySet()) {
        data.writeUTF(entry.getKey().name());
        data.writeUTF(entry.getValue().name());
      }
      data.writeUTF(decision.policyVersion().value());
      writeEvidence(data, decision.evidenceReferences());
      data.writeUTF(decision.correlationId().value());
      data.writeUTF(decision.causationId().value());
    }

    private static FinalDecision readDecision(DataInputStream data) throws IOException {
      FinalAction action = FinalAction.valueOf(data.readUTF());
      int count = data.readInt();
      Map<ClearanceBranch, ClearanceOutcome> outcomes = new EnumMap<>(ClearanceBranch.class);
      for (int index = 0; index < count; index++)
        outcomes.put(
            ClearanceBranch.valueOf(data.readUTF()), ClearanceOutcome.valueOf(data.readUTF()));
      return new FinalDecision(
          action,
          outcomes,
          new PolicyRef(data.readUTF()),
          readEvidence(data),
          new CorrelationId(data.readUTF()),
          new CausationId(data.readUTF()));
    }

    private static void writeExceptionDecision(DataOutputStream data, ExceptionDecision decision)
        throws IOException {
      data.writeUTF(decision.actorId().value());
      data.writeUTF(decision.actorRole().value());
      data.writeUTF(decision.decision().name());
      data.writeUTF(decision.reason());
      writeInstant(data, decision.decidedAt());
      data.writeUTF(decision.correlationId().value());
      data.writeUTF(decision.causationId().value());
    }

    private static ExceptionDecision readExceptionDecision(DataInputStream data)
        throws IOException {
      return new ExceptionDecision(
          new com.sevenewf.workflow.domain.common.ActorId(data.readUTF()),
          new TeamOrRoleRef(data.readUTF()),
          ExceptionDecisionType.valueOf(data.readUTF()),
          data.readUTF(),
          readInstant(data),
          new CorrelationId(data.readUTF()),
          new CausationId(data.readUTF()));
    }

    private static void writeAuthorization(
        DataOutputStream data, KeyReleaseAuthorization authorization) throws IOException {
      data.writeUTF(authorization.authorizationId().value());
      data.writeUTF(authorization.requestId().value());
      writeEvidence(data, authorization.evidenceReferences());
      writeInstant(data, authorization.authorizedAt());
    }

    private static KeyReleaseAuthorization readAuthorization(DataInputStream data)
        throws IOException {
      return new KeyReleaseAuthorization(
          new AuthorizationId(data.readUTF()),
          new KeyHandoverRequestId(data.readUTF()),
          readEvidence(data),
          readInstant(data));
    }

    private static void writeNotification(DataOutputStream data, NotificationState notification)
        throws IOException {
      data.writeUTF(notification.idempotencyKey().value());
      data.writeUTF(notification.status().name());
      data.writeInt(notification.attemptCount());
      data.writeBoolean(notification.lastFailureReference().isPresent());
      if (notification.lastFailureReference().isPresent())
        data.writeUTF(notification.lastFailureReference().orElseThrow().value());
    }

    private static NotificationState readNotification(DataInputStream data) throws IOException {
      IdempotencyKey key = new IdempotencyKey(data.readUTF());
      NotificationDeliveryStatus status = NotificationDeliveryStatus.valueOf(data.readUTF());
      int attempts = data.readInt();
      Optional<FailureReference> failure =
          data.readBoolean() ? Optional.of(new FailureReference(data.readUTF())) : Optional.empty();
      return new NotificationState(key, status, attempts, failure);
    }

    private static void writeAudit(DataOutputStream data, AuditRecord audit) throws IOException {
      data.writeUTF(audit.eventType());
      data.writeUTF(audit.requestId().value());
      data.writeInt(audit.stateVersion().value());
      data.writeUTF(audit.correlationId().value());
      data.writeUTF(audit.causationId().value());
      data.writeUTF(audit.actorId().value());
      writeInstant(data, audit.occurredAt());
      writeEvidence(data, audit.evidenceReferences());
      writeStrings(data, audit.metadata());
    }

    private static AuditRecord readAudit(DataInputStream data) throws IOException {
      return new AuditRecord(
          data.readUTF(),
          new KeyHandoverRequestId(data.readUTF()),
          new DomainVersion(data.readInt()),
          new CorrelationId(data.readUTF()),
          new CausationId(data.readUTF()),
          new com.sevenewf.workflow.domain.common.ActorId(data.readUTF()),
          readInstant(data),
          readEvidence(data),
          readStringsMap(data));
    }

    private static void writeEvidence(DataOutputStream data, List<EvidenceReference> evidence)
        throws IOException {
      data.writeInt(evidence.size());
      for (EvidenceReference reference : evidence) data.writeUTF(reference.value());
    }

    private static List<EvidenceReference> readEvidence(DataInputStream data) throws IOException {
      int count = data.readInt();
      List<EvidenceReference> evidence = new ArrayList<>();
      for (int index = 0; index < count; index++)
        evidence.add(new EvidenceReference(data.readUTF()));
      return List.copyOf(evidence);
    }

    private static void writeStrings(DataOutputStream data, Set<String> values) throws IOException {
      data.writeInt(values.size());
      for (String value : values) data.writeUTF(value);
    }

    private static void writeStrings(DataOutputStream data, Map<String, String> values)
        throws IOException {
      data.writeInt(values.size());
      for (Map.Entry<String, String> entry : values.entrySet()) {
        data.writeUTF(entry.getKey());
        data.writeUTF(entry.getValue());
      }
    }

    private static Set<String> readStrings(DataInputStream data) throws IOException {
      int count = data.readInt();
      Set<String> values = new HashSet<>();
      for (int index = 0; index < count; index++) values.add(data.readUTF());
      return Set.copyOf(values);
    }

    private static Map<String, String> readStringsMap(DataInputStream data) throws IOException {
      int count = data.readInt();
      Map<String, String> values = new HashMap<>();
      for (int index = 0; index < count; index++) values.put(data.readUTF(), data.readUTF());
      return Map.copyOf(values);
    }

    private static void writeOptionalChild(DataOutputStream data, Optional<ChildWorkflowId> value)
        throws IOException {
      data.writeBoolean(value.isPresent());
      if (value.isPresent()) data.writeUTF(value.orElseThrow().value());
    }

    private static Optional<ChildWorkflowId> readOptionalChild(DataInputStream data)
        throws IOException {
      return data.readBoolean()
          ? Optional.of(new ChildWorkflowId(data.readUTF()))
          : Optional.empty();
    }

    private static void writeOptionalActor(
        DataOutputStream data, Optional<com.sevenewf.workflow.domain.common.ActorId> value)
        throws IOException {
      data.writeBoolean(value.isPresent());
      if (value.isPresent()) data.writeUTF(value.orElseThrow().value());
    }

    private static Optional<com.sevenewf.workflow.domain.common.ActorId> readOptionalActor(
        DataInputStream data) throws IOException {
      return data.readBoolean()
          ? Optional.of(new com.sevenewf.workflow.domain.common.ActorId(data.readUTF()))
          : Optional.empty();
    }

    private static void writeInstant(DataOutputStream data, Instant instant) throws IOException {
      data.writeLong(instant.getEpochSecond());
      data.writeInt(instant.getNano());
    }

    private static Instant readInstant(DataInputStream data) throws IOException {
      return Instant.ofEpochSecond(data.readLong(), data.readInt());
    }

    private static void writeOptionalInstant(DataOutputStream data, Optional<Instant> value)
        throws IOException {
      data.writeBoolean(value.isPresent());
      if (value.isPresent()) writeInstant(data, value.orElseThrow());
    }

    private static Optional<Instant> readOptionalInstant(DataInputStream data) throws IOException {
      return data.readBoolean() ? Optional.of(readInstant(data)) : Optional.empty();
    }
  }

  public static final class SyntheticPropertyConnector implements PropertyConnector {
    private final Set<PropertyReference> properties = new HashSet<>();

    public SyntheticPropertyConnector(PropertyReference property) {
      properties.add(property);
    }

    public boolean propertyExists(PropertyReference property) {
      return properties.contains(property);
    }
  }

  public static final class SyntheticOwnerIdentityConnector implements OwnerIdentityConnector {
    private final Map<PropertyReference, OwnerReference> owners = new HashMap<>();

    public SyntheticOwnerIdentityConnector(PropertyReference property, OwnerReference owner) {
      owners.put(property, owner);
    }

    public boolean ownerMatchesProperty(OwnerReference owner, PropertyReference property) {
      return owner.equals(owners.get(property));
    }
  }

  public static final class SyntheticInspectionConnector implements InspectionConnector {
    private InspectionStatus status = new InspectionStatus(true, Optional.empty());
    private final Map<IdempotencyKey, ChildWorkflowId> children = new HashMap<>();
    private int failures;

    public void setInspectionStatus(InspectionStatus status) {
      this.status = status;
    }

    public void failTransiently(int times) {
      failures = times;
    }

    public InspectionStatus inspectionStatus(PropertyReference property) {
      if (failures-- > 0) throw new TransientConnectorException("synthetic inspection failure");
      return status;
    }

    public ChildWorkflowId startInspectionChildWorkflow(
        PropertyReference property, IdempotencyKey key) {
      return children.computeIfAbsent(key, value -> new ChildWorkflowId("child-" + value.value()));
    }

    public int childWorkflowCount() {
      return children.size();
    }
  }

  public static final class SyntheticFinanceConnector implements FinanceConnector {
    private FinanceClearance clearance =
        new FinanceClearance(
            BigDecimal.ZERO,
            ClearanceOutcome.GREEN,
            new EvidenceReference("evidence-finance-green"));
    private int calls;
    private int failures;

    public void setClearance(FinanceClearance clearance) {
      this.clearance = clearance;
    }

    public void failTransiently(int times) {
      failures = times;
    }

    public FinanceClearance financeClearance(PropertyReference property, OwnerReference owner) {
      calls++;
      if (failures-- > 0) throw new TransientConnectorException("synthetic finance failure");
      return clearance;
    }

    public int calls() {
      return calls;
    }
  }

  public static final class SyntheticLegalConnector implements LegalConnector {
    private int calls;

    public EvidenceReference legalEvidence(PropertyReference property, OwnerReference owner) {
      calls++;
      return new EvidenceReference("evidence-legal-synthetic");
    }

    public int calls() {
      return calls;
    }
  }

  public static final class SyntheticEvidenceStore implements EvidenceStore {
    private final Map<IdempotencyKey, EvidenceReference> evidence = new HashMap<>();
    private int calls;

    public EvidenceReference storeSyntheticEvidence(String type, IdempotencyKey key) {
      calls++;
      return evidence.computeIfAbsent(
          key, ignored -> new EvidenceReference("evidence-" + type + "-" + key.value()));
    }

    public int calls() {
      return calls;
    }
  }

  public static final class DeterministicDecisionService implements DecisionService {
    private final PolicyRef policy;

    public DeterministicDecisionService(PolicyRef policy) {
      this.policy = policy;
    }

    public FinalDecision decide(
        KeyHandoverState state,
        List<EvidenceReference> evidence,
        CorrelationId correlationId,
        CausationId causationId) {
      Set<ClearanceBranch> required =
          EnumSet.of(ClearanceBranch.HANDOVER, ClearanceBranch.FINANCE, ClearanceBranch.LEGAL);
      if (!state.branches().keySet().containsAll(required))
        throw new IllegalStateException(
            "Cannot decide final outcome: required branches are missing");
      Map<ClearanceBranch, ClearanceOutcome> outcomes = new EnumMap<>(ClearanceBranch.class);
      state
          .branches()
          .forEach((branch, task) -> outcomes.put(branch, task.outcome().orElseThrow()));
      FinalAction action =
          outcomes.values().stream().anyMatch(value -> value == ClearanceOutcome.RED)
              ? FinalAction.HOLD
              : outcomes.values().stream().allMatch(value -> value == ClearanceOutcome.GREEN)
                  ? FinalAction.AUTHORIZE_RELEASE
                  : FinalAction.EXCEPTION_APPROVAL_REQUIRED;
      return new FinalDecision(action, outcomes, policy, evidence, correlationId, causationId);
    }
  }

  public static final class RecordingNotificationConnector implements NotificationConnector {
    private final Set<IdempotencyKey> delivered = new HashSet<>();
    private boolean fail;

    public void failNext() {
      fail = true;
    }

    public void sendReleaseAuthorization(
        KeyReleaseAuthorization authorization, IdempotencyKey key) {
      if (fail) {
        fail = false;
        throw new TransientConnectorException("synthetic notification failure");
      }
      delivered.add(key);
    }

    public int sentCount() {
      return delivered.size();
    }
  }

  public static final class RecordingAuditSink implements AuditSink {
    private final List<AuditRecord> records = new ArrayList<>();
    private boolean fail;

    public void failNext() {
      fail = true;
    }

    public void emit(AuditRecord audit) {
      if (fail) {
        fail = false;
        throw new IllegalStateException("synthetic audit sink failure");
      }
      records.add(audit);
    }

    public boolean hasEvent(String event) {
      return records.stream().anyMatch(record -> record.eventType().equals(event));
    }

    public List<AuditRecord> records() {
      return List.copyOf(records);
    }
  }

  public static final class PermissionAuthorizationService implements AuthorizationService {
    public void require(Actor actor, Permission permission) {
      if (!actor.can(permission))
        throw new AuthorizationDeniedException("Permission denied: " + permission);
    }
  }

  public static final class NonExpandingDelegationPolicy implements DelegationPolicy {
    public void verifyDelegationDoesNotIncreaseAuthority(Actor delegator, Actor delegate) {
      if (!delegator.authorityScopes().containsAll(delegate.authorityScopes()))
        throw new AuthorizationDeniedException("Delegation must not increase authority");
    }
  }
}
