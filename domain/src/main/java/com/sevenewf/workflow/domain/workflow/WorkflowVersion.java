package com.sevenewf.workflow.domain.workflow;

import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import java.time.Instant;

public record WorkflowVersion(
    WorkflowVersionId id,
    WorkflowDefinitionId workflowDefinitionId,
    DomainVersion version,
    Instant publishedAt,
    DataClassification dataClassification) {
  public WorkflowVersion {
    Validation.requirePresent(id, "id");
    Validation.requirePresent(workflowDefinitionId, "workflowDefinitionId");
    Validation.requirePresent(version, "version");
    Validation.requirePresent(publishedAt, "publishedAt");
    Validation.requirePresent(dataClassification, "dataClassification");
  }
}
