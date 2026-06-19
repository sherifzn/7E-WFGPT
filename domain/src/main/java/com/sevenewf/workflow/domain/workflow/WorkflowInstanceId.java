package com.sevenewf.workflow.domain.workflow;

import com.sevenewf.workflow.domain.common.Validation;

public record WorkflowInstanceId(String value) {
  public WorkflowInstanceId {
    Validation.requireText(value, "workflowInstanceId");
  }
}
