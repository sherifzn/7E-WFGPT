package com.sevenewf.workflow.domain.workflowdefinition;

import com.sevenewf.workflow.domain.common.Validation;
import java.util.List;
import java.util.Map;

public record DecisionActivity(
    String activityKey,
    String displayName,
    String description,
    String inputSchemaRef,
    String outputSchemaRef,
    String requiredRoleRef,
    String retryPolicyRef,
    String timeoutRef,
    Map<String, String> metadata,
    String decisionKey,
    String ruleSetRef,
    List<String> namedOutcomes)
    implements ActivityDefinition {

  public DecisionActivity {
    activityKey = Validation.requireText(activityKey, "activityKey");
    displayName = Validation.requireText(displayName, "displayName");
    description = description == null ? "" : description;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    namedOutcomes = namedOutcomes == null ? List.of() : List.copyOf(namedOutcomes);
  }

  @Override
  public ActivityType type() {
    return ActivityType.DECISION;
  }
}
