package com.sevenewf.workflow.domain.workflow;

import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import java.util.List;

public record WorkflowDefinition(
    WorkflowDefinitionId id,
    DomainVersion definitionVersion,
    String key,
    String displayName,
    DataClassification dataClassification,
    List<ActivityDefinition> activities) {
  public WorkflowDefinition {
    Validation.requirePresent(id, "id");
    Validation.requirePresent(definitionVersion, "definitionVersion");
    key = Validation.requireText(key, "key");
    displayName = Validation.requireText(displayName, "displayName");
    Validation.requirePresent(dataClassification, "dataClassification");
    activities = Validation.requireNonEmptyList(activities, "activities");
  }
}
