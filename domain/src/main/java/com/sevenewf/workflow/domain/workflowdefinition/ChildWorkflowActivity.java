package com.sevenewf.workflow.domain.workflowdefinition;

import com.sevenewf.workflow.domain.common.Validation;
import java.util.Map;

public record ChildWorkflowActivity(
    String activityKey,
    String displayName,
    String description,
    String inputSchemaRef,
    String outputSchemaRef,
    String requiredRoleRef,
    String retryPolicyRef,
    String timeoutRef,
    Map<String, String> metadata,
    String childWorkflowKey,
    String versionConstraint,
    String correlationKeyMapping,
    String businessKeyMapping,
    DuplicatePolicy duplicatePolicy,
    String parentWaitingBehavior)
    implements ActivityDefinition {

  public ChildWorkflowActivity {
    activityKey = Validation.requireText(activityKey, "activityKey");
    displayName = Validation.requireText(displayName, "displayName");
    description = description == null ? "" : description;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  @Override
  public ActivityType type() {
    return ActivityType.CHILD_WORKFLOW;
  }
}
