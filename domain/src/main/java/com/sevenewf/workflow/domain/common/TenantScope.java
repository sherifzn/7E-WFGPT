package com.sevenewf.workflow.domain.common;

public record TenantScope(String value) {
  public TenantScope {
    Validation.requireText(value, "tenantScope");
  }
}
