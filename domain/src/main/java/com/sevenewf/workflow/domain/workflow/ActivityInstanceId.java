package com.sevenewf.workflow.domain.workflow;

import com.sevenewf.workflow.domain.common.Validation;

public record ActivityInstanceId(String value) {
  public ActivityInstanceId {
    Validation.requireText(value, "activityInstanceId");
  }
}
