package com.sevenewf.workflow.domain.workflow;

import com.sevenewf.workflow.domain.common.Validation;

public record ActivityDefinitionId(String value) {
  public ActivityDefinitionId {
    Validation.requireText(value, "activityDefinitionId");
  }
}
