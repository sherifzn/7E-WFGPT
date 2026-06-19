package com.sevenewf.workflow.backend.logging;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

final class LogRedactorTest {
  @Test
  void redactsCredentialsAndOraclePayloadMarkers() {
    LogRedactor redactor = new LogRedactor();

    String output =
        redactor.redact(
            "token=abc123 password=hunter2 oracleFinancialPayload={restricted} regular=value");

    Assertions.assertFalse(output.contains("abc123"));
    Assertions.assertFalse(output.contains("hunter2"));
    Assertions.assertFalse(output.contains("{restricted}"));
    Assertions.assertTrue(output.contains("regular=value"));
  }
}
