package com.sevenewf.workflow.domain.workflowdefinition;

import java.util.List;

public record ValidationResult(List<ValidationFinding> findings) {
  public ValidationResult {
    findings = findings == null ? List.of() : List.copyOf(findings);
  }

  public boolean isValid() {
    return findings.stream()
        .noneMatch(
            f ->
                f.severity() == ValidationSeverity.ERROR
                    || f.severity() == ValidationSeverity.CRITICAL);
  }

  public List<ValidationFinding> errors() {
    return findings.stream()
        .filter(
            f ->
                f.severity() == ValidationSeverity.ERROR
                    || f.severity() == ValidationSeverity.CRITICAL)
        .toList();
  }

  public List<ValidationFinding> warnings() {
    return findings.stream().filter(f -> f.severity() == ValidationSeverity.WARNING).toList();
  }

  public List<ValidationFinding> infos() {
    return findings.stream().filter(f -> f.severity() == ValidationSeverity.INFO).toList();
  }
}
