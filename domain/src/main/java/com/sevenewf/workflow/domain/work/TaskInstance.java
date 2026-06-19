package com.sevenewf.workflow.domain.work;

import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.common.Validation;
import com.sevenewf.workflow.domain.workflow.ActivityInstanceId;
import java.time.Instant;
import java.util.Optional;

public record TaskInstance(
    TaskInstanceId id,
    TaskTypeId taskTypeId,
    ActivityInstanceId activityInstanceId,
    TaskInstanceStatus status,
    Optional<TeamId> assignedTeamId,
    Instant createdAt,
    DataClassification dataClassification) {
  public TaskInstance {
    Validation.requirePresent(id, "id");
    Validation.requirePresent(taskTypeId, "taskTypeId");
    Validation.requirePresent(activityInstanceId, "activityInstanceId");
    Validation.requirePresent(status, "status");
    assignedTeamId = assignedTeamId == null ? Optional.empty() : assignedTeamId;
    Validation.requirePresent(createdAt, "createdAt");
    Validation.requirePresent(dataClassification, "dataClassification");
  }
}
