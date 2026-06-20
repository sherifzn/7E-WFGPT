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
  void processOwnerCanApproveOrRejectAnExceptionWithAuditableConcurrency(
      @TempDir Path temporaryDirectory) {
    KeyHandoverDemoService approvedService = new KeyHandoverDemoService(temporaryDirectory);
    KeyHandoverDemoService.ApiResponse exception = completeToException(approvedService);
    int expectedVersion = stateVersion(exception.body());

    assertEquals(
        403,
        approvedService
            .handle(
                "POST",
                "/KH-102/exception/approve",
                Map.of(
                    "actor", "legalOfficer",
                    "reason", "Synthetic exception review",
                    "expectedStateVersion", String.valueOf(expectedVersion)))
            .status());
    KeyHandoverDemoService.ApiResponse approved =
        approvedService.handle(
            "POST",
            "/KH-102/exception/approve",
            Map.of(
                "actor", "processOwner",
                "reason", "Synthetic exception review",
                "expectedStateVersion", String.valueOf(expectedVersion)));
    assertEquals(200, approved.status());
    assertTrue(approved.body().contains("Authorized"));
    assertTrue(approved.body().contains("Delivered"));
    assertTrue(approved.body().contains("release-khr-KH-102"));
    assertEquals(
        200,
        approvedService
            .handle(
                "POST",
                "/KH-102/exception/approve",
                Map.of(
                    "actor", "processOwner",
                    "reason", "Synthetic exception review",
                    "expectedStateVersion", String.valueOf(expectedVersion)))
            .status());
    assertEquals(
        409,
        approvedService
            .handle(
                "POST",
                "/KH-102/exception/reject",
                Map.of(
                    "actor", "processOwner",
                    "reason", "Different decision",
                    "expectedStateVersion", String.valueOf(expectedVersion)))
            .status());
    String audit = approvedService.handle("GET", "/KH-102/audit", Map.of()).body();
    assertTrue(audit.contains("UnauthorizedExceptionDecisionAttempt"));
    assertTrue(audit.contains("ExceptionApprovalRequested"));
    assertTrue(audit.contains("ExceptionApproved"));
    assertTrue(audit.contains("AuthorizationCreated"));
    assertTrue(audit.contains("NotificationTriggered"));
    assertTrue(audit.contains("DuplicateExceptionDecisionAccepted"));
    assertTrue(audit.contains("ConflictingDuplicateExceptionDecisionRejected"));

    KeyHandoverDemoService rejectedService = new KeyHandoverDemoService(temporaryDirectory.resolve("rejected"));
    KeyHandoverDemoService.ApiResponse rejectedException = completeToException(rejectedService);
    KeyHandoverDemoService.ApiResponse rejected =
        rejectedService.handle(
            "POST",
            "/KH-102/exception/reject",
            Map.of(
                "actor", "processOwner",
                "reason", "Synthetic rejection reason",
                "expectedStateVersion", String.valueOf(stateVersion(rejectedException.body()))));
    assertEquals(200, rejected.status());
    assertTrue(rejected.body().contains("Exception rejected"));
    assertTrue(rejected.body().contains("Synthetic rejection reason"));
    assertTrue(rejected.body().contains("\"authorizationId\":\"\""));
    assertTrue(rejected.body().contains("\"notification\":\"Not started\""));
    assertTrue(rejectedService.handle("GET", "/KH-102/audit", Map.of()).body().contains("ExceptionRejected"));
  }

  private static KeyHandoverDemoService.ApiResponse completeToException(KeyHandoverDemoService service) {
    service.handle("POST", "/KH-102/tasks/handover/claim", Map.of("actor", "handoverOfficer"));
    service.handle(
        "POST",
        "/KH-102/tasks/handover/complete",
        Map.of("outcome", "GREEN", "actor", "handoverOfficer"));
    service.handle("POST", "/KH-102/tasks/finance/complete", Map.of("actor", "financeOfficer"));
    service.handle("POST", "/KH-102/tasks/legal/claim", Map.of("actor", "legalOfficer"));
    return service.handle(
        "POST",
        "/KH-102/tasks/legal/complete",
        Map.of("outcome", "AMBER", "actor", "legalOfficer"));
  }

  private static int stateVersion(String body) {
    return Integer.parseInt(body.replaceFirst(".*\\\"stateVersion\\\":(\\d+).*", "$1"));
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
