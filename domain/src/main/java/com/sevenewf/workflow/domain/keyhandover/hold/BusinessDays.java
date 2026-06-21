package com.sevenewf.workflow.domain.keyhandover.hold;

import com.sevenewf.workflow.domain.common.Validation;

public record BusinessDays(int value) {
  public BusinessDays {
    Validation.requirePositive(value, "businessDays");
  }

  public boolean exceeds(BusinessDays other) {
    Validation.requirePresent(other, "other");
    return value > other.value;
  }
}
