package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import java.time.Instant;

public record TeamMembership(
    TeamMembershipId id,
    DomainVersion version,
    TeamId teamId,
    ActorId memberId,
    TeamMembershipStatus status,
    Instant effectiveFrom,
    DataClassification dataClassification) {
  public TeamMembership {
    Validation.requirePresent(id, "id");
    Validation.requirePresent(version, "version");
    Validation.requirePresent(teamId, "teamId");
    Validation.requirePresent(memberId, "memberId");
    Validation.requirePresent(status, "status");
    Validation.requirePresent(effectiveFrom, "effectiveFrom");
    Validation.requirePresent(dataClassification, "dataClassification");
  }
}
