package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.Validation;

public record EscalationPolicyId(String value) {
  public EscalationPolicyId {
    Validation.requireText(value, "escalationPolicyId");
  }
}
