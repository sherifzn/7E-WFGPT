package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import java.time.Duration;

public record SlaPolicy(
    SlaPolicyId id,
    DomainVersion version,
    TaskTypeId taskTypeId,
    Duration warningAfter,
    Duration breachAfter,
    DataClassification dataClassification) {
  public SlaPolicy {
    Validation.requirePresent(id, "id");
    Validation.requirePresent(version, "version");
    Validation.requirePresent(taskTypeId, "taskTypeId");
    warningAfter = Validation.requirePositive(warningAfter, "warningAfter");
    breachAfter = Validation.requirePositive(breachAfter, "breachAfter");
    if (breachAfter.compareTo(warningAfter) <= 0) {
      throw new IllegalArgumentException("breachAfter must be greater than warningAfter");
    }
    Validation.requirePresent(dataClassification, "dataClassification");
  }
}
