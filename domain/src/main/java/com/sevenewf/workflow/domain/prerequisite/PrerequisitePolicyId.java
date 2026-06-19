package com.sevenewf.workflow.domain.prerequisite;

import com.sevenewf.workflow.domain.common.Validation;

public record PrerequisitePolicyId(String value) {
  public PrerequisitePolicyId {
    Validation.requireText(value, "prerequisitePolicyId");
  }
}
