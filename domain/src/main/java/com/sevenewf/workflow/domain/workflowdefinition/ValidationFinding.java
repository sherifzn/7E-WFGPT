package com.sevenewf.workflow.domain.workflowdefinition;

import java.util.Objects;
import java.util.Optional;

public record ValidationFinding(
    ValidationSeverity severity,
    String code,
    String message,
    String workflowKey,
    Optional<String> activityKey,
    Optional<Transition> transition) {

  public ValidationFinding {
    Objects.requireNonNull(severity, "severity is required");
    Objects.requireNonNull(code, "code is required");
    Objects.requireNonNull(message, "message is required");
    Objects.requireNonNull(workflowKey, "workflowKey is required");
    activityKey = activityKey == null ? Optional.empty() : activityKey;
    transition = transition == null ? Optional.empty() : transition;
  }

  public static ValidationFinding of(
      ValidationSeverity severity,
      String code,
      String message,
      String workflowKey,
      String activityKey) {
    return new ValidationFinding(
        severity, code, message, workflowKey, Optional.ofNullable(activityKey), Optional.empty());
  }

  public static ValidationFinding of(
      ValidationSeverity severity,
      String code,
      String message,
      String workflowKey,
      Transition transition) {
    return new ValidationFinding(
        severity, code, message, workflowKey, Optional.empty(), Optional.ofNullable(transition));
  }

  public static ValidationFinding of(
      ValidationSeverity severity, String code, String message, String workflowKey) {
    return new ValidationFinding(
        severity, code, message, workflowKey, Optional.empty(), Optional.empty());
  }
}
