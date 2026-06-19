package com.sevenewf.workflow.domain.audit;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.ActorType;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.TenantScope;
import com.sevenewf.workflow.domain.common.TraceId;
import com.sevenewf.workflow.domain.common.Validation;
import com.sevenewf.workflow.domain.work.TaskInstanceId;
import com.sevenewf.workflow.domain.workflow.WorkflowInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public record AuditEvent(
    AuditEventId eventId,
    String eventType,
    DomainVersion eventVersion,
    Instant occurredAt,
    CorrelationId correlationId,
    CausationId causationId,
    Optional<WorkflowInstanceId> workflowInstanceId,
    Optional<TaskInstanceId> taskInstanceId,
    ActorType actorType,
    ActorId actorId,
    TenantScope tenantScope,
    DataClassification dataClassification,
    List<String> policyVersions,
    TraceId traceId) {
  public AuditEvent {
    Validation.requirePresent(eventId, "eventId");
    eventType = Validation.requireText(eventType, "eventType");
    Validation.requirePresent(eventVersion, "eventVersion");
    Validation.requirePresent(occurredAt, "occurredAt");
    Validation.requirePresent(correlationId, "correlationId");
    Validation.requirePresent(causationId, "causationId");
    workflowInstanceId = workflowInstanceId == null ? Optional.empty() : workflowInstanceId;
    taskInstanceId = taskInstanceId == null ? Optional.empty() : taskInstanceId;
    Validation.requirePresent(actorType, "actorType");
    Validation.requirePresent(actorId, "actorId");
    Validation.requirePresent(tenantScope, "tenantScope");
    Validation.requirePresent(dataClassification, "dataClassification");
    policyVersions = Validation.requireNonEmptyList(policyVersions, "policyVersions");
    policyVersions.forEach(policyVersion -> Validation.requireText(policyVersion, "policyVersion"));
    Validation.requirePresent(traceId, "traceId");
  }
}
