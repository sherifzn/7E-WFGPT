package com.sevenewf.workflow.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

final class ForbiddenProductionIntegrationTest {
  private static final List<String> FORBIDDEN_MARKERS =
      List.of(
          "jdbc:oracle:thin",
          "oracle.jdbc",
          "production credential",
          "prod password",
          "real customer",
          "model api key");

  @Test
  void sourceDoesNotReferenceProductionOracleOrAiCredentials() throws IOException {
    Path root = Path.of(System.getProperty("user.dir")).getParent();

    try (Stream<Path> paths = Files.walk(root)) {
      List<Path> offenders =
          paths
              .filter(Files::isRegularFile)
              .filter(ForbiddenProductionIntegrationTest::isScannedFile)
              .filter(ForbiddenProductionIntegrationTest::containsForbiddenMarker)
              .toList();

      Assertions.assertTrue(
          offenders.isEmpty(), "Forbidden production markers found: " + offenders);
    }
  }

  private static boolean isScannedFile(Path path) {
    String normalized = path.toString();
    return (normalized.contains("/backend/src/")
            || normalized.contains("/contracts/src/")
            || normalized.contains("/frontend/src/"))
        && !normalized.contains("/.git/")
        && !normalized.contains("/node_modules/")
        && !normalized.contains("/target/")
        && !normalized.contains("/dist/")
        && !normalized.endsWith("ForbiddenProductionIntegrationTest.java");
  }

  private static boolean containsForbiddenMarker(Path path) {
    try {
      String text = Files.readString(path).toLowerCase(Locale.ROOT);
      return FORBIDDEN_MARKERS.stream().anyMatch(text::contains);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to scan " + path, exception);
    }
  }
}
