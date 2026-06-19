package com.sevenewf.workflow.domain.decision;

import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import java.time.Instant;
import java.util.List;

public record DecisionResult(
    DecisionResultId id,
    DecisionRequestId decisionRequestId,
    DomainVersion resultVersion,
    DecisionOutcome outcome,
    List<String> outputReferenceKeys,
    Instant evaluatedAt,
    DataClassification dataClassification) {
  public DecisionResult {
    Validation.requirePresent(id, "id");
    Validation.requirePresent(decisionRequestId, "decisionRequestId");
    Validation.requirePresent(resultVersion, "resultVersion");
    Validation.requirePresent(outcome, "outcome");
    outputReferenceKeys =
        Validation.requireNonEmptyList(outputReferenceKeys, "outputReferenceKeys");
    outputReferenceKeys.forEach(
        reference -> Validation.requireText(reference, "outputReferenceKey"));
    Validation.requirePresent(evaluatedAt, "evaluatedAt");
    Validation.requirePresent(dataClassification, "dataClassification");
  }
}
