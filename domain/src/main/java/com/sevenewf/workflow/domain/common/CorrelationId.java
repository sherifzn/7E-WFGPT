package com.sevenewf.workflow.domain.common;

public record CorrelationId(String value) {
  public CorrelationId {
    Validation.requireText(value, "correlationId");
  }
}
