package com.sevenewf.workflow.domain.workflow;

import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.TenantScope;
import com.sevenewf.workflow.domain.common.Validation;
import java.time.Instant;

public record WorkflowInstance(
    WorkflowInstanceId id,
    DomainVersion stateVersion,
    WorkflowVersionId workflowVersionId,
    WorkflowInstanceStatus status,
    TenantScope tenantScope,
    CorrelationId correlationId,
    Instant createdAt,
    DataClassification dataClassification) {
  public WorkflowInstance {
    Validation.requirePresent(id, "id");
    Validation.requirePresent(stateVersion, "stateVersion");
    Validation.requirePresent(workflowVersionId, "workflowVersionId");
    Validation.requirePresent(status, "status");
    Validation.requirePresent(tenantScope, "tenantScope");
    Validation.requirePresent(correlationId, "correlationId");
    Validation.requirePresent(createdAt, "createdAt");
    Validation.requirePresent(dataClassification, "dataClassification");
  }
}
