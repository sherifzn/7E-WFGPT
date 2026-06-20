package com.sevenewf.workflow.domain.keyhandover;

public final class KeyHandoverExceptions {
  private KeyHandoverExceptions() {}

  public static final class AuthorizationDeniedException extends RuntimeException {
    public AuthorizationDeniedException(String message) {
      super(message);
    }
  }

  public static final class ValidationFailedException extends RuntimeException {
    public ValidationFailedException(String message) {
      super(message);
    }
  }

  public static final class OptimisticStateConflictException extends RuntimeException {
    public OptimisticStateConflictException(String message) {
      super(message);
    }
  }

  public static final class ConflictingDuplicateCompletionException extends RuntimeException {
    public ConflictingDuplicateCompletionException(String message) {
      super(message);
    }
  }

  public static final class ConflictingExceptionDecisionException extends RuntimeException {
    public ConflictingExceptionDecisionException(String message) {
      super(message);
    }
  }

  public static final class TransientConnectorException extends RuntimeException {
    public TransientConnectorException(String message) {
      super(message);
    }
  }

  public static final class RetryExhaustedException extends RuntimeException {
    public RetryExhaustedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
