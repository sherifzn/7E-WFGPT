package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.Validation;

public record DelegationId(String value) {
  public DelegationId {
    Validation.requireText(value, "delegationId");
  }
}
