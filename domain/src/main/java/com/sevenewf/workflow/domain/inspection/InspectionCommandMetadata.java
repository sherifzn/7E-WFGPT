package com.sevenewf.workflow.domain.inspection;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionRole;
import java.time.Instant;

public record InspectionCommandMetadata(
    ActorId actorId,
    InspectionRole actorRole,
    DomainVersion expectedProcessVersion,
    CorrelationId correlationId,
    CausationId causationId,
    Instant commandedAt) {
  public InspectionCommandMetadata {
    Validation.requirePresent(actorId, "actorId");
    Validation.requirePresent(actorRole, "actorRole");
    Validation.requirePresent(expectedProcessVersion, "expectedProcessVersion");
    Validation.requirePresent(correlationId, "correlationId");
    Validation.requirePresent(causationId, "causationId");
    Validation.requirePresent(commandedAt, "commandedAt");
  }
}
