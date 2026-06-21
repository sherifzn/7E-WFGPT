package com.sevenewf.workflow.backend.inspection;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InspectionDemoServiceTest {

  @Test
  void processOwnerCannotClaimInspection(@TempDir Path temporaryDirectory) {
    InspectionDemoService service = new InspectionDemoService(temporaryDirectory);
    InspectionDemoService.ApiResponse created = createProcess(service);
    String inspectionId = extractId(created.body());
    String taskId = inspectionId + "-inspection-1";

    InspectionDemoService.ApiResponse result =
        service.handle(
            "POST",
            "/" + inspectionId + "/tasks/" + taskId + "/claim",
            Map.of("actor", "processOwner"));

    assertEquals(403, result.status());
    assertTrue(result.body().contains("not authorized"));
  }

  @Test
  void processOwnerCannotCompleteInspection(@TempDir Path temporaryDirectory) {
    InspectionDemoService service = new InspectionDemoService(temporaryDirectory);
    InspectionDemoService.ApiResponse created = createProcess(service);
    String inspectionId = extractId(created.body());
    String taskId = inspectionId + "-inspection-1";
    service.handle(
        "POST",
        "/" + inspectionId + "/tasks/" + taskId + "/claim",
        Map.of("actor", "inspectionOfficer"));

    InspectionDemoService.ApiResponse result =
        service.handle(
            "POST",
            "/" + inspectionId + "/tasks/" + taskId + "/complete-passed",
            Map.of("actor", "processOwner"));

    assertEquals(403, result.status());
  }

  @Test
  void processOwnerCannotClaimRemediation(@TempDir Path temporaryDirectory) {
    InspectionDemoService service = new InspectionDemoService(temporaryDirectory);
    InspectionDemoService.ApiResponse created = createProcess(service);
    String inspectionId = extractId(created.body());
    String taskId = inspectionId + "-inspection-1";
    service.handle(
        "POST",
        "/" + inspectionId + "/tasks/" + taskId + "/claim",
        Map.of("actor", "inspectionOfficer"));
    service.handle(
        "POST",
        "/" + inspectionId + "/tasks/" + taskId + "/complete-failed",
        Map.of("actor", "inspectionOfficer"));
    String remediationTaskId = inspectionId + "-remediation-1";

    InspectionDemoService.ApiResponse result =
        service.handle(
            "POST",
            "/" + inspectionId + "/tasks/" + remediationTaskId + "/claim-remediation",
            Map.of("actor", "processOwner"));

    assertEquals(403, result.status());
  }

  @Test
  void inspectionOfficerCanClaimAndPassInspection(@TempDir Path temporaryDirectory) {
    InspectionDemoService service = new InspectionDemoService(temporaryDirectory);
    InspectionDemoService.ApiResponse created = createProcess(service);
    String inspectionId = extractId(created.body());
    String taskId = inspectionId + "-inspection-1";

    InspectionDemoService.ApiResponse claimed =
        service.handle(
            "POST",
            "/" + inspectionId + "/tasks/" + taskId + "/claim",
            Map.of("actor", "inspectionOfficer"));
    assertEquals(200, claimed.status());

    InspectionDemoService.ApiResponse passed =
        service.handle(
            "POST",
            "/" + inspectionId + "/tasks/" + taskId + "/complete-passed",
            Map.of("actor", "inspectionOfficer"));
    assertEquals(200, passed.status());
    assertTrue(passed.body().contains("COMPLETED"));
  }

  @Test
  void remediationOfficerCanClaimAndCompleteRemediation(@TempDir Path temporaryDirectory) {
    InspectionDemoService service = new InspectionDemoService(temporaryDirectory);
    InspectionDemoService.ApiResponse created = createProcess(service);
    String inspectionId = extractId(created.body());
    String taskId = inspectionId + "-inspection-1";
    service.handle(
        "POST",
        "/" + inspectionId + "/tasks/" + taskId + "/claim",
        Map.of("actor", "inspectionOfficer"));
    service.handle(
        "POST",
        "/" + inspectionId + "/tasks/" + taskId + "/complete-failed",
        Map.of("actor", "inspectionOfficer"));
    String remediationTaskId = inspectionId + "-remediation-1";

    InspectionDemoService.ApiResponse claimed =
        service.handle(
            "POST",
            "/" + inspectionId + "/tasks/" + remediationTaskId + "/claim-remediation",
            Map.of("actor", "remediationOfficer"));
    assertEquals(200, claimed.status());

    InspectionDemoService.ApiResponse completed =
        service.handle(
            "POST",
            "/" + inspectionId + "/tasks/" + remediationTaskId + "/complete-remediation",
            Map.of("actor", "remediationOfficer"));
    assertEquals(200, completed.status());
    assertTrue(completed.body().contains("WAITING_FOR_REINSPECTION"));
  }

  @Test
  void unauthorizedAttemptIsAudited(@TempDir Path temporaryDirectory) {
    InspectionDemoService service = new InspectionDemoService(temporaryDirectory);
    InspectionDemoService.ApiResponse created = createProcess(service);
    String inspectionId = extractId(created.body());
    String taskId = inspectionId + "-inspection-1";

    service.handle(
        "POST",
        "/" + inspectionId + "/tasks/" + taskId + "/claim",
        Map.of("actor", "processOwner"));

    InspectionDemoService.ApiResponse history =
        service.handle("GET", "/" + inspectionId + "/history", Map.of());
    assertEquals(200, history.status());
    assertTrue(history.body().contains("UnauthorizedActionRejected"));
    assertTrue(history.body().contains("processOwner"));
  }

  @Test
  void historyContainsLifecycleEvents(@TempDir Path temporaryDirectory) {
    InspectionDemoService service = new InspectionDemoService(temporaryDirectory);
    InspectionDemoService.ApiResponse created = createProcess(service);
    String inspectionId = extractId(created.body());
    String taskId = inspectionId + "-inspection-1";
    service.handle(
        "POST",
        "/" + inspectionId + "/tasks/" + taskId + "/claim",
        Map.of("actor", "inspectionOfficer"));
    service.handle(
        "POST",
        "/" + inspectionId + "/tasks/" + taskId + "/complete-passed",
        Map.of("actor", "inspectionOfficer"));

    InspectionDemoService.ApiResponse history =
        service.handle("GET", "/" + inspectionId + "/history", Map.of());
    assertEquals(200, history.status());
    String body = history.body();
    assertTrue(body.contains("InspectionRequested"));
    assertTrue(body.contains("InspectionTaskCreated"));
    assertTrue(body.contains("InspectionTaskClaimed"));
    assertTrue(body.contains("InspectionCompleted"));
    assertTrue(body.contains("InspectionPassed"));
    assertTrue(body.contains("ParentWorkflowResumeRequested"));
  }

  @Test
  void historySurvivesRestart(@TempDir Path temporaryDirectory) {
    InspectionDemoService service = new InspectionDemoService(temporaryDirectory);
    InspectionDemoService.ApiResponse created = createProcess(service);
    String inspectionId = extractId(created.body());
    String taskId = inspectionId + "-inspection-1";
    service.handle(
        "POST",
        "/" + inspectionId + "/tasks/" + taskId + "/claim",
        Map.of("actor", "inspectionOfficer"));
    service.handle(
        "POST",
        "/" + inspectionId + "/tasks/" + taskId + "/complete-passed",
        Map.of("actor", "inspectionOfficer"));

    InspectionDemoService restarted = new InspectionDemoService(temporaryDirectory);
    InspectionDemoService.ApiResponse history =
        restarted.handle("GET", "/" + inspectionId + "/history", Map.of());
    assertEquals(200, history.status());
    assertTrue(history.body().contains("InspectionPassed"));
    assertTrue(history.body().contains("InspectionTaskClaimed"));
  }

  @Test
  void processOwnerCanCancelInspection(@TempDir Path temporaryDirectory) {
    InspectionDemoService service = new InspectionDemoService(temporaryDirectory);
    InspectionDemoService.ApiResponse created = createProcess(service);
    String inspectionId = extractId(created.body());

    InspectionDemoService.ApiResponse cancelled =
        service.handle("POST", "/" + inspectionId + "/cancel", Map.of("actor", "processOwner"));

    assertEquals(200, cancelled.status());
    assertTrue(cancelled.body().contains("CANCELLED"));
  }

  private static InspectionDemoService.ApiResponse createProcess(InspectionDemoService service) {
    return service.handle(
        "POST",
        "/",
        Map.of(
            "propertyReference", "Test Property",
            "parentRequestId", "khr-TEST-001",
            "actor", "inspectionOfficer"));
  }

  private static String extractId(String json) {
    // Extract the process id which appears first, before the businessKey field
    return json.replaceFirst(".*\"id\":\"([^\"]+)\",\"businessKey\".*", "$1");
  }
}
