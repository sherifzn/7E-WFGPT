package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.Validation;

public record TaskTypeId(String value) {
  public TaskTypeId {
    Validation.requireText(value, "taskTypeId");
  }
}
