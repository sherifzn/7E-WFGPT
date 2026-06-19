package com.sevenewf.workflow.contracts;

import com.sevenewf.workflow.domain.common.DataClassification;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

final class EventEnvelopeContractTest {
  @Test
  void supportsRequiredArchitectureEnvelopeFields() {
    EventEnvelope envelope =
        new EventEnvelope(
            "event-001",
            "BootstrapValidated",
            1,
            Instant.parse("2026-01-01T00:00:00Z"),
            "correlation-001",
            "causation-001",
            Optional.empty(),
            Optional.empty(),
            "SERVICE",
            "bootstrap-service",
            "local-development",
            DataClassification.INTERNAL,
            "policy-set:bootstrap",
            "trace-001");

    Assertions.assertEquals("BootstrapValidated", envelope.eventType());
    Assertions.assertEquals(DataClassification.INTERNAL, envelope.dataClassification());
  }
}
