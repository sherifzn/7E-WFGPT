package com.sevenewf.workflow.domain.common;

public record DomainVersion(int value) {
  public DomainVersion {
    Validation.requirePositive(value, "version");
  }
}
