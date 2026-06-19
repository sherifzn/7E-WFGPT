package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import java.util.List;

public record EscalationPolicy(
    EscalationPolicyId id,
    DomainVersion version,
    TaskTypeId taskTypeId,
    List<String> escalationRuleKeys,
    DataClassification dataClassification) {
  public EscalationPolicy {
    Validation.requirePresent(id, "id");
    Validation.requirePresent(version, "version");
    Validation.requirePresent(taskTypeId, "taskTypeId");
    escalationRuleKeys = Validation.requireNonEmptyList(escalationRuleKeys, "escalationRuleKeys");
    escalationRuleKeys.forEach(ruleKey -> Validation.requireText(ruleKey, "escalationRuleKey"));
    Validation.requirePresent(dataClassification, "dataClassification");
  }
}
