package com.sevenewf.workflow.domain.workflowdefinition;

public enum DuplicatePolicy {
  CREATE_NEW,
  CORRELATE_ACTIVE,
  REUSE_VALID_COMPLETED
}
