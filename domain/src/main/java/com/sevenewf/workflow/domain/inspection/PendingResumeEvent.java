package com.sevenewf.workflow.domain.inspection;

import com.sevenewf.workflow.domain.common.Validation;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EvidenceReference;
import java.time.Instant;

public record PendingResumeEvent(
    InspectionProcessId processId,
    int passedAttemptNumber,
    EvidenceReference evidenceReference,
    boolean handled,
    Instant createdAt) {
  public PendingResumeEvent {
    Validation.requirePresent(processId, "processId");
    Validation.requirePositive(passedAttemptNumber, "passedAttemptNumber");
    Validation.requirePresent(evidenceReference, "evidenceReference");
    Validation.requirePresent(createdAt, "createdAt");
  }

  public PendingResumeEvent markHandled() {
    return new PendingResumeEvent(
        processId, passedAttemptNumber, evidenceReference, true, createdAt);
  }
}
