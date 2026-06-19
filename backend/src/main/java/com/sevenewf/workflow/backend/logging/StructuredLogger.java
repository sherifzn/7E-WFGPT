package com.sevenewf.workflow.backend.logging;

import java.time.Instant;

public final class StructuredLogger {
  private final String component;
  private final LogRedactor redactor;

  private StructuredLogger(String component, LogRedactor redactor) {
    this.component = component;
    this.redactor = redactor;
  }

  public static StructuredLogger forComponent(String component) {
    return new StructuredLogger(component, new LogRedactor());
  }

  public void info(String event, String message) {
    System.out.println(format("INFO", event, message));
  }

  String format(String level, String event, String message) {
    return "{"
        + "\"occurredAt\":\""
        + Instant.now()
        + "\","
        + "\"level\":\""
        + escape(level)
        + "\","
        + "\"component\":\""
        + escape(component)
        + "\","
        + "\"event\":\""
        + escape(event)
        + "\","
        + "\"message\":\""
        + escape(redactor.redact(message))
        + "\""
        + "}";
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
