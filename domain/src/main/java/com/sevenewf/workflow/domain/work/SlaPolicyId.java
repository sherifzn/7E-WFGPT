package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.Validation;

public record SlaPolicyId(String value) {
  public SlaPolicyId {
    Validation.requireText(value, "slaPolicyId");
  }
}
