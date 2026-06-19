package com.sevenewf.workflow.domain.common;

public record CausationId(String value) {
  public CausationId {
    Validation.requireText(value, "causationId");
  }
}
