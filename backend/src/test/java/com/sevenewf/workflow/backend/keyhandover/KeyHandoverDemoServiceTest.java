package com.sevenewf.workflow.backend.keyhandover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class KeyHandoverDemoServiceTest {
  @Test
  void createsAndRetrievesSyntheticRequest() {
    KeyHandoverDemoService service = new KeyHandoverDemoService();

    KeyHandoverDemoService.ApiResponse created =
        service.handle(
            "POST",
            "/",
            Map.of("propertyReference", "Demo Property 201", "ownerReference", "Demo Owner 201"));

    assertEquals(201, created.status());
    assertTrue(created.body().contains("KH-105"));
    assertEquals(200, service.handle("GET", "/KH-105", Map.of()).status());
  }

  @Test
  void inspectionBarrierAndHandoverCompletionUseDomainService() {
    KeyHandoverDemoService service = new KeyHandoverDemoService();

    assertEquals(400, service.handle("POST", "/KH-101/tasks/handover/claim", Map.of()).status());
    assertEquals(200, service.handle("POST", "/KH-101/inspection/resume", Map.of()).status());
    assertEquals(200, service.handle("POST", "/KH-101/tasks/handover/claim", Map.of()).status());
    KeyHandoverDemoService.ApiResponse completed =
        service.handle("POST", "/KH-101/tasks/handover/complete", Map.of("outcome", "GREEN"));
    assertEquals(200, completed.status());
    assertTrue(completed.body().contains("Completed"));
  }

  @Test
  void financeLegalDecisionAndNotificationRetryStayConnectorBacked() {
    KeyHandoverDemoService service = new KeyHandoverDemoService();

    service.handle("POST", "/KH-102/tasks/handover/claim", Map.of());
    service.handle("POST", "/KH-102/tasks/handover/complete", Map.of("outcome", "GREEN"));
    KeyHandoverDemoService.ApiResponse finance =
        service.handle("POST", "/KH-102/tasks/finance/complete", Map.of());
    assertEquals(200, finance.status());
    service.handle("POST", "/KH-102/tasks/legal/claim", Map.of());
    KeyHandoverDemoService.ApiResponse result =
        service.handle("POST", "/KH-102/tasks/legal/complete", Map.of("outcome", "AMBER"));
    assertTrue(result.body().contains("Exception approval required"));

    KeyHandoverDemoService.ApiResponse retry =
        service.handle("POST", "/KH-104/notification/retry", Map.of());
    assertEquals(200, retry.status());
    assertTrue(retry.body().contains("Delivered"));
  }
}
