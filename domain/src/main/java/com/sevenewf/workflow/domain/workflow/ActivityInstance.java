package com.sevenewf.workflow.domain.workflow;

import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.Validation;
import java.time.Instant;

public record ActivityInstance(
    ActivityInstanceId id,
    WorkflowInstanceId workflowInstanceId,
    ActivityDefinitionId activityDefinitionId,
    ActivityInstanceStatus status,
    Instant createdAt,
    DataClassification dataClassification) {
  public ActivityInstance {
    Validation.requirePresent(id, "id");
    Validation.requirePresent(workflowInstanceId, "workflowInstanceId");
    Validation.requirePresent(activityDefinitionId, "activityDefinitionId");
    Validation.requirePresent(status, "status");
    Validation.requirePresent(createdAt, "createdAt");
    Validation.requirePresent(dataClassification, "dataClassification");
  }
}
