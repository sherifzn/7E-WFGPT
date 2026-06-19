package com.sevenewf.workflow.domain.workflow;

import com.sevenewf.workflow.domain.common.Validation;

public record WorkflowDefinitionId(String value) {
  public WorkflowDefinitionId {
    Validation.requireText(value, "workflowDefinitionId");
  }
}
