package com.sevenewf.workflow.backend.logging;

import java.util.List;
import java.util.regex.Pattern;

public final class LogRedactor {
  private static final String REDACTION = "[REDACTED]";
  private static final List<Pattern> SENSITIVE_PATTERNS =
      List.of(
          Pattern.compile("(?i)(password|token|secret|credential)=([^\\s,}]+)"),
          Pattern.compile("(?i)(authorization: bearer )([^\\s,}]+)"),
          Pattern.compile("(?i)(oracle[^\\s,}]*payload=)([^\\s,}]+)"));

  public String redact(String message) {
    String redacted = message == null ? "" : message;
    for (Pattern pattern : SENSITIVE_PATTERNS) {
      redacted = pattern.matcher(redacted).replaceAll("$1" + REDACTION);
    }
    return redacted;
  }
}
