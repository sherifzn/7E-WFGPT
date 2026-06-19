package com.sevenewf.workflow.domain.work;

public enum TaskInstanceStatus {
  CREATED,
  QUEUED,
  ASSIGNED,
  CLAIMED,
  STARTED,
  PAUSED,
  COMPLETED,
  CANCELLED,
  ESCALATED
}
