package com.sevenewf.workflow.domain.workflowdefinition;

public enum ActivityType {
  START,
  HUMAN_TASK,
  SERVICE_TASK,
  DECISION,
  PARALLEL_SPLIT,
  PARALLEL_JOIN,
  CHILD_WORKFLOW,
  WAIT_EVENT,
  TIMER,
  END
}
