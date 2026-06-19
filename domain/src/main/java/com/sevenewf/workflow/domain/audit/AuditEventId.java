package com.sevenewf.workflow.domain.audit;

import com.sevenewf.workflow.domain.common.Validation;

public record AuditEventId(String value) {
  public AuditEventId {
    Validation.requireText(value, "auditEventId");
  }
}
