package com.sevenewf.workflow.domain.workflowdefinition;

import com.sevenewf.workflow.domain.common.Validation;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SemanticVersion implements Comparable<SemanticVersion> {
  private static final Pattern SEMVER_PATTERN =
      Pattern.compile(
          "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
              + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
              + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

  private final int major;
  private final int minor;
  private final int patch;
  private final String preRelease;
  private final String buildMetadata;
  private final String value;

  public SemanticVersion(String value) {
    Validation.requireText(value, "semanticVersion");
    Matcher matcher = SEMVER_PATTERN.matcher(value);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid semantic version: " + value);
    }
    this.major = Integer.parseInt(matcher.group(1));
    this.minor = Integer.parseInt(matcher.group(2));
    this.patch = Integer.parseInt(matcher.group(3));
    this.preRelease = matcher.group(4);
    this.buildMetadata = matcher.group(5);
    this.value = value;
  }

  public static SemanticVersion parse(String value) {
    return new SemanticVersion(value);
  }

  public int major() {
    return major;
  }

  public int minor() {
    return minor;
  }

  public int patch() {
    return patch;
  }

  public String preRelease() {
    return preRelease;
  }

  public String buildMetadata() {
    return buildMetadata;
  }

  public String value() {
    return value;
  }

  @Override
  public int compareTo(SemanticVersion other) {
    int result = Integer.compare(major, other.major);
    if (result != 0) {
      return result;
    }
    result = Integer.compare(minor, other.minor);
    if (result != 0) {
      return result;
    }
    result = Integer.compare(patch, other.patch);
    if (result != 0) {
      return result;
    }
    if (preRelease == null && other.preRelease == null) {
      return 0;
    }
    if (preRelease == null) {
      return 1;
    }
    if (other.preRelease == null) {
      return -1;
    }
    return comparePreRelease(preRelease, other.preRelease);
  }

  private static int comparePreRelease(String left, String right) {
    String[] leftParts = left.split("\\.");
    String[] rightParts = right.split("\\.");
    int length = Math.min(leftParts.length, rightParts.length);
    for (int i = 0; i < length; i++) {
      int comparison = comparePreReleasePart(leftParts[i], rightParts[i]);
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(leftParts.length, rightParts.length);
  }

  private static int comparePreReleasePart(String left, String right) {
    boolean leftNumeric = left.matches("\\d+");
    boolean rightNumeric = right.matches("\\d+");
    if (leftNumeric && rightNumeric) {
      return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
    }
    if (leftNumeric) {
      return -1;
    }
    if (rightNumeric) {
      return 1;
    }
    return left.compareTo(right);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SemanticVersion other)) {
      return false;
    }
    return major == other.major
        && minor == other.minor
        && patch == other.patch
        && Objects.equals(preRelease, other.preRelease);
  }

  @Override
  public int hashCode() {
    return Objects.hash(major, minor, patch, preRelease);
  }

  @Override
  public String toString() {
    return value;
  }
}
