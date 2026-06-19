package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.Validation;

public record TeamMembershipId(String value) {
  public TeamMembershipId {
    Validation.requireText(value, "teamMembershipId");
  }
}
