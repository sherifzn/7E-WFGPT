package com.sevenewf.workflow.domain.keyhandover.hold;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.Validation;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.ClearanceBranch;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EvidenceReference;
import java.time.Instant;

public record BranchRemediation(
    ClearanceBranch branch,
    String summary,
    EvidenceReference supportingReference,
    ActorId recordedBy,
    Instant recordedAt) {
  public BranchRemediation {
    Validation.requirePresent(branch, "branch");
    summary = Validation.requireText(summary, "summary");
    Validation.requirePresent(supportingReference, "supportingReference");
    Validation.requirePresent(recordedBy, "recordedBy");
    Validation.requirePresent(recordedAt, "recordedAt");
  }
}
