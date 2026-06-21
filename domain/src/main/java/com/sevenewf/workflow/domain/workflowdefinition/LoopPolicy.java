package com.sevenewf.workflow.domain.workflowdefinition;

import com.sevenewf.workflow.domain.common.Validation;

public record LoopPolicy(
    String loopPolicyKey,
    LoopType loopType,
    Integer maxIterations,
    String exitConditionRef,
    String policyRef,
    String timeoutPolicyRef) {

  public LoopPolicy {
    loopPolicyKey = Validation.requireText(loopPolicyKey, "loopPolicyKey");
    loopType = Validation.requirePresent(loopType, "loopType");
    switch (loopType) {
      case BOUNDED -> {
        if (maxIterations == null) {
          throw new IllegalArgumentException("maxIterations is required for BOUNDED loops");
        }
        if (maxIterations < 1) {
          throw new IllegalArgumentException("maxIterations must be positive");
        }
      }
      case CONDITION_CONTROLLED -> Validation.requireText(exitConditionRef, "exitConditionRef");
      case POLICY_CONTROLLED -> Validation.requireText(policyRef, "policyRef");
      default -> throw new IllegalArgumentException("Unsupported loop type: " + loopType);
    }
  }
}
