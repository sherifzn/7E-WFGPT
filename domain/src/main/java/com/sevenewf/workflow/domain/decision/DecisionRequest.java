package com.sevenewf.workflow.domain.decision;

import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import com.sevenewf.workflow.domain.workflow.WorkflowInstanceId;
import java.time.Instant;
import java.util.List;

public record DecisionRequest(
    DecisionRequestId id,
    DecisionDefinitionId decisionDefinitionId,
    DomainVersion decisionDefinitionVersion,
    WorkflowInstanceId workflowInstanceId,
    CorrelationId correlationId,
    List<String> inputReferenceKeys,
    Instant requestedAt,
    DataClassification dataClassification) {
  public DecisionRequest {
    Validation.requirePresent(id, "id");
    Validation.requirePresent(decisionDefinitionId, "decisionDefinitionId");
    Validation.requirePresent(decisionDefinitionVersion, "decisionDefinitionVersion");
    Validation.requirePresent(workflowInstanceId, "workflowInstanceId");
    Validation.requirePresent(correlationId, "correlationId");
    inputReferenceKeys = Validation.requireNonEmptyList(inputReferenceKeys, "inputReferenceKeys");
    inputReferenceKeys.forEach(reference -> Validation.requireText(reference, "inputReferenceKey"));
    Validation.requirePresent(requestedAt, "requestedAt");
    Validation.requirePresent(dataClassification, "dataClassification");
  }
}
