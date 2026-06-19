package com.sevenewf.workflow.domain.common;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class Validation {
  private Validation() {}

  public static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }

  public static int requirePositive(int value, String fieldName) {
    if (value < 1) {
      throw new IllegalArgumentException(fieldName + " must be positive");
    }
    return value;
  }

  public static <T> T requirePresent(T value, String fieldName) {
    return Objects.requireNonNull(value, fieldName + " is required");
  }

  public static <T> List<T> requireList(List<T> values, String fieldName) {
    if (values == null) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return List.copyOf(values);
  }

  public static <T> List<T> requireNonEmptyList(List<T> values, String fieldName) {
    List<T> copy = requireList(values, fieldName);
    if (copy.isEmpty()) {
      throw new IllegalArgumentException(fieldName + " must not be empty");
    }
    return copy;
  }

  public static Duration requirePositive(Duration value, String fieldName) {
    requirePresent(value, fieldName);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(fieldName + " must be positive");
    }
    return value;
  }

  public static Instant requireNotBefore(Instant value, Instant lowerBound, String fieldName) {
    requirePresent(value, fieldName);
    requirePresent(lowerBound, "lowerBound");
    if (value.isBefore(lowerBound)) {
      throw new IllegalArgumentException(fieldName + " must not be before lower bound");
    }
    return value;
  }
}
