package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;

public record Team(
    TeamId id,
    DomainVersion version,
    String key,
    String displayName,
    DataClassification dataClassification) {
  public Team {
    Validation.requirePresent(id, "id");
    Validation.requirePresent(version, "version");
    key = Validation.requireText(key, "key");
    displayName = Validation.requireText(displayName, "displayName");
    Validation.requirePresent(dataClassification, "dataClassification");
  }
}
