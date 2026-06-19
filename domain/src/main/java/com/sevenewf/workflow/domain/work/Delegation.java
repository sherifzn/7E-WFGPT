package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.common.Validation;
import java.time.Instant;
import java.util.List;

public record Delegation(
    DelegationId id,
    DomainVersion version,
    ActorId delegatorId,
    ActorId delegateId,
    Instant startsAt,
    Instant endsAt,
    List<String> authorityScopeKeys,
    DataClassification dataClassification) {
  public Delegation {
    Validation.requirePresent(id, "id");
    Validation.requirePresent(version, "version");
    Validation.requirePresent(delegatorId, "delegatorId");
    Validation.requirePresent(delegateId, "delegateId");
    if (delegatorId.equals(delegateId)) {
      throw new IllegalArgumentException("delegateId must differ from delegatorId");
    }
    Validation.requirePresent(startsAt, "startsAt");
    endsAt = Validation.requireNotBefore(endsAt, startsAt, "endsAt");
    if (endsAt.equals(startsAt)) {
      throw new IllegalArgumentException("endsAt must be after startsAt");
    }
    authorityScopeKeys = Validation.requireNonEmptyList(authorityScopeKeys, "authorityScopeKeys");
    authorityScopeKeys.forEach(scope -> Validation.requireText(scope, "authorityScopeKey"));
    Validation.requirePresent(dataClassification, "dataClassification");
  }
}
