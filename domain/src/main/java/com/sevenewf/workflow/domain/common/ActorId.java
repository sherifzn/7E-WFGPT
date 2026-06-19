package com.sevenewf.workflow.domain.common;

public record ActorId(String value) {
  public ActorId {
    Validation.requireText(value, "actorId");
  }
}
