package com.sevenewf.workflow.domain.workflowdefinition;

import java.util.Map;

public sealed interface ActivityDefinition
    permits StartActivity,
        EndActivity,
        HumanTaskActivity,
        ServiceTaskActivity,
        DecisionActivity,
        ParallelSplitActivity,
        ParallelJoinActivity,
        ChildWorkflowActivity,
        WaitEventActivity,
        TimerActivity {
  String activityKey();

  String displayName();

  ActivityType type();

  String description();

  String inputSchemaRef();

  String outputSchemaRef();

  String requiredRoleRef();

  String retryPolicyRef();

  String timeoutRef();

  Map<String, String> metadata();
}
