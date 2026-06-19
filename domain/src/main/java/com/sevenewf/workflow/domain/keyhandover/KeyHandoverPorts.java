package com.sevenewf.workflow.domain.keyhandover;

import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.AuditRecord;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.BusinessKey;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ChildWorkflowId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EvidenceReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.FinalDecision;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.FinanceClearance;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.IdempotencyKey;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.InspectionStatus;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverRequestId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyReleaseAuthorization;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.OwnerReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PropertyReference;
import java.time.Instant;
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
    FinalDecision decide(KeyHandoverState state, List<EvidenceReference> evidenceReferences);
  }

  public interface AuditSink {
    void emit(AuditRecord auditRecord);
  }

  public interface Clock {
    Instant now();
  }

  public interface KeyHandoverStateStore {
    Optional<KeyHandoverState> findByBusinessKey(BusinessKey businessKey);

    Optional<KeyHandoverState> findById(KeyHandoverRequestId requestId);

    KeyHandoverState insertIfAbsent(KeyHandoverState state);

    KeyHandoverState save(KeyHandoverState state, DomainVersion expectedVersion);
  }

  public interface AuthorizationService {
    void require(KeyHandoverTypes.Actor actor, KeyHandoverTypes.Permission permission);
  }

  public interface DelegationPolicy {
    void verifyDelegationDoesNotIncreaseAuthority(
        KeyHandoverTypes.Actor delegator, KeyHandoverTypes.Actor delegate);
  }
}
