package com.sevenewf.workflow.domain.workflow;

import com.sevenewf.workflow.domain.common.Validation;

public record WorkflowVersionId(String value) {
  public WorkflowVersionId {
    Validation.requireText(value, "workflowVersionId");
  }
}
