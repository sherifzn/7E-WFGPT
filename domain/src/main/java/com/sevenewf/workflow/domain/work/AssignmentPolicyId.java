package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.Validation;

public record AssignmentPolicyId(String value) {
  public AssignmentPolicyId {
    Validation.requireText(value, "assignmentPolicyId");
  }
}
