package com.sevenewf.workflow.backend.keyhandover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class KeyHandoverDemoServiceTest {
  @Test
  void createsAndRetrievesSyntheticRequest(@TempDir Path temporaryDirectory) {
    KeyHandoverDemoService service = new KeyHandoverDemoService(temporaryDirectory);

    KeyHandoverDemoService.ApiResponse created =
        service.handle(
            "POST",
            "/",
            Map.of(
                "propertyReference",
                "Demo Property 201",
                "ownerReference",
                "Demo Owner 201",
                "actor",
                "requester"));

    assertEquals(201, created.status());
    assertTrue(created.body().contains("KH-105"));
    assertEquals(200, service.handle("GET", "/KH-105", Map.of()).status());
  }

  @Test
  void inspectionBarrierAndHandoverCompletionUseDomainService(@TempDir Path temporaryDirectory) {
    KeyHandoverDemoService service = new KeyHandoverDemoService(temporaryDirectory);

    assertEquals(
        403,
        service
            .handle("POST", "/KH-101/tasks/handover/claim", Map.of("actor", "requester"))
            .status());
    assertEquals(
        200,
        service.handle("POST", "/KH-101/inspection/resume", Map.of("actor", "requester")).status());
    assertEquals(
        200,
        service
            .handle("POST", "/KH-101/tasks/handover/claim", Map.of("actor", "handoverOfficer"))
            .status());
    KeyHandoverDemoService.ApiResponse completed =
        service.handle(
            "POST",
            "/KH-101/tasks/handover/complete",
            Map.of("outcome", "GREEN", "actor", "handoverOfficer"));
    assertEquals(200, completed.status());
    assertTrue(completed.body().contains("Completed"));
  }

  @Test
  void financeLegalDecisionAndNotificationRetryStayConnectorBacked(
      @TempDir Path temporaryDirectory) {
    KeyHandoverDemoService service = new KeyHandoverDemoService(temporaryDirectory);

    service.handle("POST", "/KH-102/tasks/handover/claim", Map.of("actor", "handoverOfficer"));
    service.handle(
        "POST",
        "/KH-102/tasks/handover/complete",
        Map.of("outcome", "GREEN", "actor", "handoverOfficer"));
    KeyHandoverDemoService.ApiResponse finance =
        service.handle("POST", "/KH-102/tasks/finance/complete", Map.of("actor", "financeOfficer"));
    assertEquals(200, finance.status());
    service.handle("POST", "/KH-102/tasks/legal/claim", Map.of("actor", "legalOfficer"));
    KeyHandoverDemoService.ApiResponse result =
        service.handle(
            "POST",
            "/KH-102/tasks/legal/complete",
            Map.of("outcome", "AMBER", "actor", "legalOfficer"));
    assertTrue(result.body().contains("Exception approval required"));

    KeyHandoverDemoService.ApiResponse retry =
        service.handle("POST", "/KH-104/notification/retry", Map.of("actor", "processOwner"));
    assertEquals(200, retry.status());
    assertTrue(retry.body().contains("Delivered"));
  }

  @Test
  void localDevelopmentDataSurvivesServiceRestart(@TempDir Path temporaryDirectory) {
    KeyHandoverDemoService initial = new KeyHandoverDemoService(temporaryDirectory);
    KeyHandoverDemoService.ApiResponse created =
        initial.handle(
            "POST",
            "/",
            Map.of(
                "propertyReference",
                "Demo Property 301",
                "ownerReference",
                "Demo Owner 301",
                "actor",
                "requester"));

    assertEquals(201, created.status());

    KeyHandoverDemoService restarted = new KeyHandoverDemoService(temporaryDirectory);
    assertTrue(restarted.handle("GET", "/", Map.of()).body().contains("Demo Property 301"));
    assertEquals(
        200,
        restarted
            .handle("POST", "/KH-105/inspection/resume", Map.of("actor", "requester"))
            .status());
    assertEquals(
        200,
        restarted
            .handle("POST", "/KH-105/tasks/handover/claim", Map.of("actor", "handoverOfficer"))
            .status());
    assertEquals(
        200,
        restarted
            .handle(
                "POST",
                "/KH-105/tasks/handover/complete",
                Map.of("outcome", "GREEN", "actor", "handoverOfficer"))
            .status());
  }
}
