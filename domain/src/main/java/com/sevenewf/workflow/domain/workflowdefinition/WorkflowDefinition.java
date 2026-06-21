package com.sevenewf.workflow.domain.workflowdefinition;

import com.sevenewf.workflow.domain.common.Validation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record WorkflowDefinition(
    String workflowKey,
    String displayName,
    String description,
    SemanticVersion version,
    WorkflowDefinitionStatus status,
    List<ActivityDefinition> activities,
    List<Transition> transitions,
    String startActivityKey,
    List<String> terminalActivityKeys,
    Map<String, String> metadata,
    Instant createdAt,
    String createdBy,
    String correlationKeyDefinition,
    String idempotencyKeyDefinition) {

  public WorkflowDefinition {
    workflowKey = Validation.requireText(workflowKey, "workflowKey");
    displayName = Validation.requireText(displayName, "displayName");
    description = description == null ? "" : description;
    version = Validation.requirePresent(version, "version");
    status = Validation.requirePresent(status, "status");
    activities = Validation.requireNonEmptyList(activities, "activities");
    transitions = Validation.requireList(transitions, "transitions");
    startActivityKey = Validation.requireText(startActivityKey, "startActivityKey");
    terminalActivityKeys =
        Validation.requireNonEmptyList(terminalActivityKeys, "terminalActivityKeys");
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    createdAt = Validation.requirePresent(createdAt, "createdAt");
    createdBy = Validation.requireText(createdBy, "createdBy");
    correlationKeyDefinition =
        Validation.requireText(correlationKeyDefinition, "correlationKeyDefinition");
    idempotencyKeyDefinition =
        Validation.requireText(idempotencyKeyDefinition, "idempotencyKeyDefinition");
  }

  public boolean isPublished() {
    return status == WorkflowDefinitionStatus.PUBLISHED;
  }

  public boolean isTerminal(String activityKey) {
    return terminalActivityKeys.contains(activityKey);
  }

  public ActivityDefinition findActivity(String activityKey) {
    return activities.stream()
        .filter(a -> Objects.equals(a.activityKey(), activityKey))
        .findFirst()
        .orElse(null);
  }
}
