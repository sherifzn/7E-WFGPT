package com.sevenewf.workflow.domain.decision;

import com.sevenewf.workflow.domain.common.Validation;

public record DecisionDefinitionId(String value) {
  public DecisionDefinitionId {
    Validation.requireText(value, "decisionDefinitionId");
  }
}
