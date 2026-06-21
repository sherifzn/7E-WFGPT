package com.sevenewf.workflow.domain.keyhandover;

import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.*;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public final class KeyHandoverPorts {
  private KeyHandoverPorts() {}

  public interface PropertyConnector {
    boolean propertyExists(PropertyReference propertyReference);
  }

  public interface OwnerIdentityConnector {
    boolean ownerMatchesProperty(
        OwnerReference ownerReference, PropertyReference propertyReference);
  }

  public interface InspectionConnector {
    InspectionStatus inspectionStatus(PropertyReference propertyReference);

    ChildWorkflowId startInspectionChildWorkflow(
        PropertyReference propertyReference, IdempotencyKey idempotencyKey);
  }

  public interface FinanceConnector {
    FinanceClearance financeClearance(
        PropertyReference propertyReference, OwnerReference ownerReference);
  }

  public interface LegalConnector {
    EvidenceReference legalEvidence(
        PropertyReference propertyReference, OwnerReference ownerReference);
  }

  public interface NotificationConnector {
    void sendReleaseAuthorization(
        KeyReleaseAuthorization authorization, IdempotencyKey idempotencyKey);
  }

  public interface EvidenceStore {
    EvidenceReference storeSyntheticEvidence(String evidenceType, IdempotencyKey idempotencyKey);
  }

  public interface DecisionService {
    FinalDecision decide(
        KeyHandoverState state,
        List<EvidenceReference> evidenceReferences,
        com.sevenewf.workflow.domain.common.CorrelationId correlationId,
        com.sevenewf.workflow.domain.common.CausationId causationId);
  }

  public interface AuditSink {
    void emit(AuditRecord auditRecord);
  }

  public interface Clock {
    java.time.Instant now();
  }

  public interface RetryScheduler {
    void backoff(Duration delay, int failedAttempt, String operation);
  }

  public interface AutomaticAssignmentService {
    KeyHandoverTypes.Actor assign(KeyHandoverTypes.HumanTaskPolicy taskPolicy);
  }

  public interface KeyHandoverStateStore {
    Optional<KeyHandoverState> findByBusinessKey(BusinessKey businessKey);

    Optional<KeyHandoverState> findById(KeyHandoverRequestId requestId);

    KeyHandoverState insertIfAbsent(KeyHandoverState state, List<AuditRecord> pendingAudits);

    KeyHandoverState commit(
        KeyHandoverState state, DomainVersion expectedVersion, List<AuditRecord> pendingAudits);

    void appendPendingAudit(AuditRecord auditRecord);

    List<AuditRecord> pendingAudits();

    void markAuditDelivered(AuditRecord auditRecord);
  }

  public interface AuthorizationService {
    void require(Actor actor, Permission permission);
  }

  public interface DelegationPolicy {
    void verifyDelegationDoesNotIncreaseAuthority(Actor delegator, Actor delegate);
  }
}
