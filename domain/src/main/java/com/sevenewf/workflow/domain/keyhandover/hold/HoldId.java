package com.sevenewf.workflow.domain.keyhandover.hold;

import com.sevenewf.workflow.domain.common.Validation;

public record HoldId(String value) {
  public HoldId {
    Validation.requireText(value, "holdId");
  }
}
