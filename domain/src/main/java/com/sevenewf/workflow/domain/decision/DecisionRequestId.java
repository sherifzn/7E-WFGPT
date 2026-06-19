package com.sevenewf.workflow.domain.decision;

import com.sevenewf.workflow.domain.common.Validation;

public record DecisionRequestId(String value) {
  public DecisionRequestId {
    Validation.requireText(value, "decisionRequestId");
  }
}
