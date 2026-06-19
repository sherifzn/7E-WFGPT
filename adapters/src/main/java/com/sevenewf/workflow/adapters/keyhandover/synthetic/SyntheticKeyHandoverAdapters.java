package com.sevenewf.workflow.adapters.keyhandover.synthetic;

import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.AuthorizationDeniedException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.OptimisticStateConflictException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverExceptions.TransientConnectorException;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.AuditSink;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.AuthorizationService;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.Clock;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.DecisionService;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.DelegationPolicy;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.EvidenceStore;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.FinanceConnector;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.InspectionConnector;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.KeyHandoverStateStore;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.LegalConnector;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.NotificationConnector;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.OwnerIdentityConnector;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverPorts.PropertyConnector;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverState;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.Actor;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.AuditRecord;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.BusinessKey;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ChildWorkflowId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ClearanceBranch;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ClearanceOutcome;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EvidenceReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.FinalAction;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.FinalDecision;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.FinanceClearance;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.IdempotencyKey;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.InspectionStatus;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverRequestId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyReleaseAuthorization;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.OwnerReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.Permission;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PolicyRef;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PropertyReference;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SyntheticKeyHandoverAdapters {
  private SyntheticKeyHandoverAdapters() {}

  public static final class ControllableClock implements Clock {
    private Instant now;

    public ControllableClock(Instant now) {
      this.now = now;
    }

    @Override
    public Instant now() {
      return now;
    }

    public void setNow(Instant now) {
      this.now = now;
    }
  }

  public static final class InMemoryDurableKeyHandoverStateStore implements KeyHandoverStateStore {
    private final Map<KeyHandoverRequestId, KeyHandoverState> byId = new HashMap<>();
    private final Map<BusinessKey, KeyHandoverRequestId> byBusinessKey = new HashMap<>();

    @Override
    public Optional<KeyHandoverState> findByBusinessKey(BusinessKey businessKey) {
      return Optional.ofNullable(byBusinessKey.get(businessKey)).flatMap(this::findById);
    }

    @Override
    public Optional<KeyHandoverState> findById(KeyHandoverRequestId requestId) {
      return Optional.ofNullable(byId.get(requestId));
    }

    @Override
    public KeyHandoverState insertIfAbsent(KeyHandoverState state) {
      if (byBusinessKey.containsKey(state.businessKey())) {
        return byId.get(byBusinessKey.get(state.businessKey()));
      }
      byId.put(state.requestId(), state);
      byBusinessKey.put(state.businessKey(), state.requestId());
      return state;
    }

    @Override
    public KeyHandoverState save(KeyHandoverState state, DomainVersion expectedVersion) {
      KeyHandoverState current = byId.get(state.requestId());
      if (current == null || !current.stateVersion().equals(expectedVersion)) {
        throw new OptimisticStateConflictException("State version conflict");
      }
      byId.put(state.requestId(), state);
      byBusinessKey.put(state.businessKey(), state.requestId());
      return state;
    }
  }

  public static final class SyntheticPropertyConnector implements PropertyConnector {
    private final Set<PropertyReference> properties = new HashSet<>();

    public SyntheticPropertyConnector(PropertyReference propertyReference) {
      properties.add(propertyReference);
    }

    @Override
    public boolean propertyExists(PropertyReference propertyReference) {
      return properties.contains(propertyReference);
    }
  }

  public static final class SyntheticOwnerIdentityConnector implements OwnerIdentityConnector {
    private final Map<PropertyReference, OwnerReference> ownersByProperty = new HashMap<>();

    public SyntheticOwnerIdentityConnector(
        PropertyReference propertyReference, OwnerReference ownerReference) {
      ownersByProperty.put(propertyReference, ownerReference);
    }

    @Override
    public boolean ownerMatchesProperty(
        OwnerReference ownerReference, PropertyReference propertyReference) {
      return ownerReference.equals(ownersByProperty.get(propertyReference));
    }
  }

  public static final class SyntheticInspectionConnector implements InspectionConnector {
    private InspectionStatus inspectionStatus = new InspectionStatus(true, Optional.empty());
    private final Map<IdempotencyKey, ChildWorkflowId> childrenByKey = new HashMap<>();
    private int transientFailuresRemaining;

    public void setInspectionStatus(InspectionStatus inspectionStatus) {
      this.inspectionStatus = inspectionStatus;
    }

    public void failTransiently(int times) {
      transientFailuresRemaining = times;
    }

    @Override
    public InspectionStatus inspectionStatus(PropertyReference propertyReference) {
      if (transientFailuresRemaining > 0) {
        transientFailuresRemaining--;
        throw new TransientConnectorException("synthetic inspection failure");
      }
      return inspectionStatus;
    }

    @Override
    public ChildWorkflowId startInspectionChildWorkflow(
        PropertyReference propertyReference, IdempotencyKey idempotencyKey) {
      return childrenByKey.computeIfAbsent(
          idempotencyKey, key -> new ChildWorkflowId("child-" + key.value()));
    }

    public int childWorkflowCount() {
      return childrenByKey.size();
    }
  }

  public static final class SyntheticFinanceConnector implements FinanceConnector {
    private FinanceClearance clearance =
        new FinanceClearance(
            BigDecimal.ZERO,
            ClearanceOutcome.GREEN,
            new EvidenceReference("evidence-finance-green"));
    private int transientFailuresRemaining;

    public void setClearance(FinanceClearance clearance) {
      this.clearance = clearance;
    }

    public void failTransiently(int times) {
      transientFailuresRemaining = times;
    }

    @Override
    public FinanceClearance financeClearance(
        PropertyReference propertyReference, OwnerReference ownerReference) {
      if (transientFailuresRemaining > 0) {
        transientFailuresRemaining--;
        throw new TransientConnectorException("synthetic finance failure");
      }
      return clearance;
    }
  }

  public static final class SyntheticLegalConnector implements LegalConnector {
    @Override
    public EvidenceReference legalEvidence(
        PropertyReference propertyReference, OwnerReference ownerReference) {
      return new EvidenceReference("evidence-legal-synthetic");
    }
  }

  public static final class SyntheticEvidenceStore implements EvidenceStore {
    private final Map<IdempotencyKey, EvidenceReference> evidenceByKey = new HashMap<>();

    @Override
    public EvidenceReference storeSyntheticEvidence(
        String evidenceType, IdempotencyKey idempotencyKey) {
      return evidenceByKey.computeIfAbsent(
          idempotencyKey,
          key -> new EvidenceReference("evidence-" + evidenceType + "-" + key.value()));
    }
  }

  public static final class DeterministicDecisionService implements DecisionService {
    private final PolicyRef policyVersion;

    public DeterministicDecisionService(PolicyRef policyVersion) {
      this.policyVersion = policyVersion;
    }

    @Override
    public FinalDecision decide(
        KeyHandoverState state, List<EvidenceReference> evidenceReferences) {
      Map<ClearanceBranch, ClearanceOutcome> outcomes = new HashMap<>();
      state
          .branches()
          .forEach(
              (branch, branchState) -> outcomes.put(branch, branchState.outcome().orElseThrow()));
      FinalAction action;
      if (outcomes.values().stream().anyMatch(outcome -> outcome == ClearanceOutcome.RED)) {
        action = FinalAction.HOLD;
      } else if (outcomes.values().stream()
          .allMatch(outcome -> outcome == ClearanceOutcome.GREEN)) {
        action = FinalAction.AUTHORIZE_RELEASE;
      } else {
        action = FinalAction.EXCEPTION_APPROVAL_REQUIRED;
      }
      return new FinalDecision(
          action,
          outcomes,
          policyVersion,
          evidenceReferences,
          new com.sevenewf.workflow.domain.common.CorrelationId(state.businessKey().value()),
          new com.sevenewf.workflow.domain.common.CausationId(
              "decision-" + state.requestId().value()));
    }
  }

  public static final class RecordingNotificationConnector implements NotificationConnector {
    private final Set<IdempotencyKey> sent = new HashSet<>();
    private boolean fail;

    public void failNext() {
      fail = true;
    }

    @Override
    public void sendReleaseAuthorization(
        KeyReleaseAuthorization authorization, IdempotencyKey idempotencyKey) {
      if (fail) {
        fail = false;
        throw new TransientConnectorException("synthetic notification failure");
      }
      sent.add(idempotencyKey);
    }

    public int sentCount() {
      return sent.size();
    }
  }

  public static final class RecordingAuditSink implements AuditSink {
    private final List<AuditRecord> records = new ArrayList<>();
    private boolean fail;

    public void failNext() {
      fail = true;
    }

    @Override
    public void emit(AuditRecord auditRecord) {
      if (fail) {
        fail = false;
        throw new IllegalStateException("synthetic audit sink failure");
      }
      records.add(auditRecord);
    }

    public List<AuditRecord> records() {
      return List.copyOf(records);
    }

    public boolean hasEvent(String eventType) {
      return records.stream().anyMatch(record -> record.eventType().equals(eventType));
    }
  }

  public static final class PermissionAuthorizationService implements AuthorizationService {
    @Override
    public void require(Actor actor, Permission permission) {
      if (!actor.can(permission)) {
        throw new AuthorizationDeniedException("Permission denied: " + permission);
      }
    }
  }

  public static final class NonExpandingDelegationPolicy implements DelegationPolicy {
    @Override
    public void verifyDelegationDoesNotIncreaseAuthority(Actor delegator, Actor delegate) {
      if (!delegator.authorityScopes().containsAll(delegate.authorityScopes())) {
        throw new AuthorizationDeniedException("Delegation must not increase authority");
      }
    }
  }
}
