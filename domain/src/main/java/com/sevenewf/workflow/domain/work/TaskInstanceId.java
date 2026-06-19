package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.Validation;

public record TaskInstanceId(String value) {
  public TaskInstanceId {
    Validation.requireText(value, "taskInstanceId");
  }
}
