package com.sevenewf.workflow.domain.decision;

import com.sevenewf.workflow.domain.common.Validation;

public record DecisionResultId(String value) {
  public DecisionResultId {
    Validation.requireText(value, "decisionResultId");
  }
}
