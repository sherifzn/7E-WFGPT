package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.Validation;

public record TeamId(String value) {
  public TeamId {
    Validation.requireText(value, "teamId");
  }
}
