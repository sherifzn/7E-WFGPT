package com.sevenewf.workflow.domain.workflowdefinition;

import com.sevenewf.workflow.domain.common.Validation;
import java.util.Map;

public record ServiceTaskActivity(
    String activityKey,
    String displayName,
    String description,
    String inputSchemaRef,
    String outputSchemaRef,
    String requiredRoleRef,
    String retryPolicyRef,
    String timeoutRef,
    Map<String, String> metadata,
    String connectorKey,
    String operationKey,
    String serviceRetryPolicyRef,
    boolean idempotencyRequired,
    String inputContractRef,
    String outputContractRef)
    implements ActivityDefinition {

  public ServiceTaskActivity {
    activityKey = Validation.requireText(activityKey, "activityKey");
    displayName = Validation.requireText(displayName, "displayName");
    description = description == null ? "" : description;
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }

  @Override
  public ActivityType type() {
    return ActivityType.SERVICE_TASK;
  }
}
