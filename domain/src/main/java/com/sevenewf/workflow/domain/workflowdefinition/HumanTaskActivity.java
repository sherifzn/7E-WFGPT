package com.sevenewf.workflow.domain.workflowdefinition;

import com.sevenewf.workflow.domain.common.Validation;
import java.util.List;
import java.util.Map;

public record HumanTaskActivity(
    String activityKey,
    String displayName,
    String description,
    String inputSchemaRef,
    String outputSchemaRef,
    String requiredRoleRef,
    String retryPolicyRef,
    String timeoutRef,
    Map<String, String> metadata,
    String taskTypeKey,
    String eligibleRoleKey,
    List<String> allowedOutcomes,
    List<String> requiredEvidenceTypes,
    String assignmentPolicyRef,
    String slaPolicyRef)
    implements ActivityDefinition {

  public HumanTaskActivity {
    activityKey = Validation.requireText(activityKey, "activityKey");
    displayName = Validation.requireText(displayName, "displayName");
    description = description == null ? "" : description;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    allowedOutcomes = allowedOutcomes == null ? List.of() : List.copyOf(allowedOutcomes);
    requiredEvidenceTypes =
        requiredEvidenceTypes == null ? List.of() : List.copyOf(requiredEvidenceTypes);
  }

  @Override
  public ActivityType type() {
    return ActivityType.HUMAN_TASK;
  }
}
