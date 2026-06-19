package com.sevenewf.workflow.contracts;

import com.sevenewf.workflow.domain.common.DataClassification;
import java.time.Instant;
import java.util.Optional;

public record EventEnvelope(
    String eventId,
    String eventType,
    int eventVersion,
    Instant occurredAt,
    String correlationId,
    String causationId,
    Optional<String> workflowInstanceId,
    Optional<String> taskInstanceId,
    String actorType,
    String actorId,
    String scope,
    DataClassification dataClassification,
    String policyVersions,
    String traceId) {}
