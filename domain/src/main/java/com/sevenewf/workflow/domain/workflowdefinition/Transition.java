package com.sevenewf.workflow.domain.workflowdefinition;

import com.sevenewf.workflow.domain.common.Validation;

public record Transition(
    String sourceActivityKey,
    String targetActivityKey,
    String outcome,
    String conditionRef,
    int priority,
    String label) {

  public Transition {
    sourceActivityKey = Validation.requireText(sourceActivityKey, "sourceActivityKey");
    targetActivityKey = Validation.requireText(targetActivityKey, "targetActivityKey");
    label = label == null ? "" : label;
  }
}
