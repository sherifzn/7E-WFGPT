package com.sevenewf.workflow.adapters.keyhandover.synthetic;

import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.*;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.*;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverState;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
   * Test-only same-JVM durable snapshot adapter. It writes a snapshot marker and retains state by
   * storage path across store and service reconstruction; production persistence remains an ADR.
   */
  public static final class TestOnlyPathBackedKeyHandoverStateStore
      extends InMemoryKeyHandoverStateStore {
    private static final Map<Path, SharedStorage> STORAGES = new HashMap<>();
    private final Path location;
    private final SharedStorage shared;

    public TestOnlyPathBackedKeyHandoverStateStore(Path location) {
      this.location = location.toAbsolutePath().normalize();
      synchronized (STORAGES) {
        shared = STORAGES.computeIfAbsent(this.location, ignored -> new SharedStorage());
      }
    }

    public Optional<KeyHandoverState> findByBusinessKey(BusinessKey key) {
      synchronized (shared) {
        return Optional.ofNullable(shared.byBusinessKey.get(key))
            .flatMap(id -> Optional.ofNullable(shared.byId.get(id)));
      }
    }

    public Optional<KeyHandoverState> findById(KeyHandoverRequestId id) {
      synchronized (shared) {
        return Optional.ofNullable(shared.byId.get(id));
      }
    }

    public KeyHandoverState insertIfAbsent(KeyHandoverState state, List<AuditRecord> audits) {
      synchronized (shared) {
        if (shared.byBusinessKey.containsKey(state.businessKey()))
          return shared.byId.get(shared.byBusinessKey.get(state.businessKey()));
        shared.byId.put(state.requestId(), state);
        shared.byBusinessKey.put(state.businessKey(), state.requestId());
        shared.pending.addAll(audits);
        snapshot();
        return state;
      }
    }

    public KeyHandoverState commit(
        KeyHandoverState state, DomainVersion expected, List<AuditRecord> audits) {
      synchronized (shared) {
        KeyHandoverState current = shared.byId.get(state.requestId());
        if (current == null || !current.stateVersion().equals(expected))
          throw new OptimisticStateConflictException("State version conflict");
        shared.byId.put(state.requestId(), state);
        shared.byBusinessKey.put(state.businessKey(), state.requestId());
        shared.pending.addAll(audits);
        snapshot();
        return state;
      }
    }

    public List<AuditRecord> pendingAudits() {
      synchronized (shared) {
        return List.copyOf(shared.pending);
      }
    }

    public void markAuditDelivered(AuditRecord audit) {
      synchronized (shared) {
        shared.pending.remove(audit);
        snapshot();
      }
    }

    private void snapshot() {
      try {
        Files.writeString(
            location,
            "test-only-key-handover-snapshot state="
                + shared.byId.size()
                + " pendingAudit="
                + shared.pending.size());
      } catch (java.io.IOException exception) {
        throw new IllegalStateException("Unable to write test snapshot", exception);
      }
    }

    private static final class SharedStorage {
      private final Map<KeyHandoverRequestId, KeyHandoverState> byId = new HashMap<>();
      private final Map<BusinessKey, KeyHandoverRequestId> byBusinessKey = new HashMap<>();
      private final List<AuditRecord> pending = new ArrayList<>();
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
