package com.sevenewf.workflow.domain.prerequisite;

import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import com.sevenewf.workflow.domain.workflow.WorkflowDefinitionId;
import java.util.List;

public record PrerequisitePolicy(
    PrerequisitePolicyId id,
    DomainVersion version,
    WorkflowDefinitionId workflowDefinitionId,
    List<String> capabilityKeys,
    DataClassification dataClassification) {
  public PrerequisitePolicy {
    Validation.requirePresent(id, "id");
    Validation.requirePresent(version, "version");
    Validation.requirePresent(workflowDefinitionId, "workflowDefinitionId");
    capabilityKeys = Validation.requireNonEmptyList(capabilityKeys, "capabilityKeys");
    capabilityKeys.forEach(capability -> Validation.requireText(capability, "capabilityKey"));
    Validation.requirePresent(dataClassification, "dataClassification");
  }
}
