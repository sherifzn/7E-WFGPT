package com.sevenewf.workflow.domain.inspection;

public final class InspectionApplicationExceptions {
  private InspectionApplicationExceptions() {}

  public static final class AuthorizationDeniedException extends RuntimeException {
    public AuthorizationDeniedException(String message) {
      super(message);
    }
  }

  public static final class ConflictException extends RuntimeException {
    public ConflictException(String message) {
      super(message);
    }
  }

  public static final class ProcessNotFoundException extends RuntimeException {
    public ProcessNotFoundException(String message) {
      super(message);
    }
  }
}
