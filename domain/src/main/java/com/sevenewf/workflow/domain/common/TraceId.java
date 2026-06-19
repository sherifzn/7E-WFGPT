package com.sevenewf.workflow.domain.common;

public record TraceId(String value) {
  public TraceId {
    Validation.requireText(value, "traceId");
  }
}
