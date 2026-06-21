package com.sevenewf.workflow.domain.keyhandover.hold;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import java.time.Instant;

public record HoldCommandMetadata(
    ActorId actorId,
    HoldRole actorRole,
    DomainVersion expectedStateVersion,
    CorrelationId correlationId,
    CausationId causationId,
    Instant commandedAt) {
  public HoldCommandMetadata {
    Validation.requirePresent(actorId, "actorId");
    Validation.requirePresent(actorRole, "actorRole");
    Validation.requirePresent(expectedStateVersion, "expectedStateVersion");
    Validation.requirePresent(correlationId, "correlationId");
    Validation.requirePresent(causationId, "causationId");
    Validation.requirePresent(commandedAt, "commandedAt");
  }
}
