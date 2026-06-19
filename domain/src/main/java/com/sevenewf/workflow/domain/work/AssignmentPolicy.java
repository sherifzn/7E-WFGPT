package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import java.util.List;

public record AssignmentPolicy(
    AssignmentPolicyId id,
    DomainVersion version,
    TaskTypeId taskTypeId,
    List<String> assignmentRuleKeys,
    DataClassification dataClassification) {
  public AssignmentPolicy {
    Validation.requirePresent(id, "id");
    Validation.requirePresent(version, "version");
    Validation.requirePresent(taskTypeId, "taskTypeId");
    assignmentRuleKeys = Validation.requireNonEmptyList(assignmentRuleKeys, "assignmentRuleKeys");
    assignmentRuleKeys.forEach(ruleKey -> Validation.requireText(ruleKey, "assignmentRuleKey"));
    Validation.requirePresent(dataClassification, "dataClassification");
  }
}
