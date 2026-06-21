package com.sevenewf.workflow.domain.inspection;

import static com.sevenewf.workflow.domain.inspection.InspectionApplicationExceptions.AuthorizationDeniedException;
import static com.sevenewf.workflow.domain.inspection.InspectionApplicationExceptions.ConflictException;
import static com.sevenewf.workflow.domain.inspection.InspectionApplicationService.*;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus.CANCELLED;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus.COMPLETED;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus.WAITING_FOR_REINSPECTION;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus.WAITING_FOR_REMEDIATION;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionResult.FAILED;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionResult.PASSED;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionRole.INSPECTION_OFFICER;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionRole.PROCESS_OWNER;
import static com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionRole.REMEDIATION_OFFICER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessId;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionResult;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionRole;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EvidenceReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverRequestId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PropertyReference;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class InspectionApplicationServiceTest {
  private static final ActorId INSPECTOR = new ActorId("inspection-officer");
  private static final ActorId REMEDIATOR = new ActorId("remediation-officer");
  private static final ActorId OWNER = new ActorId("process-owner");

  @Test
  void createsNewProcessWithOneInspectionTask() {
    InspectionProcess process = newService().requestOrCorrelate(request("create", 1));

    assertEquals(1, process.version().value());
    assertEquals(1, process.tasks().size());
  }

  @Test
  void correlatesActiveProcess() {
    InspectionApplicationService service = newService();
    InspectionProcess created = service.requestOrCorrelate(request("create", 1));

    InspectionProcess correlated = service.requestOrCorrelate(request("correlate", 1));

    assertSame(created, correlated);
  }

  @Test
  void claimsAndPassesInspection() {
    InspectionApplicationService service = newService();
    InspectionProcess created = service.requestOrCorrelate(request("create", 1));
    InspectionProcess claimed =
        service.claimInspection(claim(created, INSPECTION_OFFICER, INSPECTOR, 1, "claim"));

    InspectionProcess passed =
        service.completeInspectionPassed(complete(claimed, PASSED, 2, "pass"));

    assertEquals(COMPLETED, passed.status());
    assertEquals(PASSED, passed.attempts().get(0).result());
  }

  @Test
  void claimsAndFailsInspection() {
    InspectionApplicationService service = newService();
    InspectionProcess created = service.requestOrCorrelate(request("create", 1));
    InspectionProcess claimed =
        service.claimInspection(claim(created, INSPECTION_OFFICER, INSPECTOR, 1, "claim"));

    InspectionProcess failed =
        service.completeInspectionFailed(complete(claimed, FAILED, 2, "fail"));

    assertEquals(WAITING_FOR_REMEDIATION, failed.status());
    assertEquals(FAILED, failed.attempts().get(0).result());
  }

  @Test
  void failureCreatesRemediationTask() {
    InspectionProcess failed = failedProcess(newService());

    assertEquals(1, failed.remediationCycles().size());
    assertEquals("REMEDIATION", failed.tasks().get(1).type().name());
  }

  @Test
  void remediationCompletionCreatesReinspectionTask() {
    InspectionApplicationService service = newService();
    InspectionProcess failed = failedProcess(service);
    InspectionProcess claimed =
        service.claimRemediation(
            claim(failed, REMEDIATION_OFFICER, REMEDIATOR, 3, "claim-remediation"));

    InspectionProcess completed =
        service.completeRemediation(remediate(claimed, 4, "complete-remediation"));

    assertEquals(WAITING_FOR_REINSPECTION, completed.status());
    assertEquals(3, completed.tasks().size());
  }

  @Test
  void claimsAndCompletesReinspection() {
    InspectionApplicationService service = newService();
    InspectionProcess ready = reinspectionReady(service);
    InspectionProcess claimed =
        service.claimReinspection(
            claim(ready, INSPECTION_OFFICER, INSPECTOR, 5, "claim-reinspection"));

    InspectionProcess completed =
        service.completeReinspection(complete(claimed, PASSED, 6, "reinspection-pass"));

    assertEquals(COMPLETED, completed.status());
    assertEquals(2, completed.attempts().size());
  }

  @Test
  void cancelsActiveProcess() {
    InspectionApplicationService service = newService();
    InspectionProcess created = service.requestOrCorrelate(request("create", 1));

    InspectionProcess cancelled =
        service.cancel(
            new CancelInspectionCommand(
                created.id(), "synthetic reason", metadata(OWNER, PROCESS_OWNER, 1, "cancel")));

    assertEquals(CANCELLED, cancelled.status());
  }

  @Test
  void rejectsUnauthorizedRole() {
    InspectionApplicationService service = newService();
    InspectionProcess created = service.requestOrCorrelate(request("create", 1));

    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            service.claimInspection(
                claim(created, REMEDIATION_OFFICER, REMEDIATOR, 1, "unauthorized")));
  }

  @Test
  void ignoresEquivalentDuplicateCompletion() {
    InspectionApplicationService service = newService();
    InspectionProcess created = service.requestOrCorrelate(request("create", 1));
    InspectionProcess claimed =
        service.claimInspection(claim(created, INSPECTION_OFFICER, INSPECTOR, 1, "claim"));
    CompleteInspectionCommand command = complete(claimed, PASSED, 2, "pass");
    InspectionProcess completed = service.completeInspectionPassed(command);

    assertSame(completed, service.completeInspectionPassed(command));
  }

  @Test
  void rejectsConflictingDuplicateCompletion() {
    InspectionApplicationService service = newService();
    InspectionProcess created = service.requestOrCorrelate(request("create", 1));
    InspectionProcess claimed =
        service.claimInspection(claim(created, INSPECTION_OFFICER, INSPECTOR, 1, "claim"));
    InspectionProcess completed =
        service.completeInspectionFailed(complete(claimed, FAILED, 2, "fail"));

    assertThrows(
        ConflictException.class,
        () -> service.completeInspectionPassed(complete(completed, PASSED, 3, "conflicting-pass")));
  }

  @Test
  void rejectsExpectedVersionConflict() {
    InspectionApplicationService service = newService();
    InspectionProcess created = service.requestOrCorrelate(request("create", 1));
    InspectionProcess claimed =
        service.claimInspection(claim(created, INSPECTION_OFFICER, INSPECTOR, 1, "claim"));

    assertThrows(
        ConflictException.class,
        () -> service.completeInspectionPassed(complete(claimed, PASSED, 1, "stale-version")));
  }

  private static InspectionProcess failedProcess(InspectionApplicationService service) {
    InspectionProcess created = service.requestOrCorrelate(request("create", 1));
    InspectionProcess claimed =
        service.claimInspection(claim(created, INSPECTION_OFFICER, INSPECTOR, 1, "claim"));
    return service.completeInspectionFailed(complete(claimed, FAILED, 2, "fail"));
  }

  private static InspectionProcess reinspectionReady(InspectionApplicationService service) {
    InspectionProcess failed = failedProcess(service);
    InspectionProcess claimed =
        service.claimRemediation(
            claim(failed, REMEDIATION_OFFICER, REMEDIATOR, 3, "claim-remediation"));
    return service.completeRemediation(remediate(claimed, 4, "complete-remediation"));
  }

  private static InspectionRequestCommand request(String causation, int expectedVersion) {
    return new InspectionRequestCommand(
        new KeyHandoverRequestId("handover-1"),
        new PropertyReference("property-1"),
        "synthetic",
        metadata(OWNER, PROCESS_OWNER, expectedVersion, causation));
  }

  private static ClaimTaskCommand claim(
      InspectionProcess process,
      InspectionRole role,
      ActorId actor,
      int expectedVersion,
      String causation) {
    return new ClaimTaskCommand(
        process.id(),
        process.tasks().get(process.tasks().size() - 1).id(),
        metadata(actor, role, expectedVersion, causation));
  }

  private static CompleteInspectionCommand complete(
      InspectionProcess process, InspectionResult result, int expectedVersion, String causation) {
    return new CompleteInspectionCommand(
        process.id(),
        process.tasks().get(process.tasks().size() - 1).id(),
        result,
        "synthetic findings",
        new EvidenceReference("evidence-" + causation),
        metadata(INSPECTOR, INSPECTION_OFFICER, expectedVersion, causation));
  }

  private static CompleteRemediationCommand remediate(
      InspectionProcess process, int expectedVersion, String causation) {
    return new CompleteRemediationCommand(
        process.id(),
        process.tasks().get(process.tasks().size() - 1).id(),
        "synthetic resolution",
        new EvidenceReference("remediation-" + causation),
        metadata(REMEDIATOR, REMEDIATION_OFFICER, expectedVersion, causation));
  }

  private static InspectionCommandMetadata metadata(
      ActorId actor, InspectionRole role, int expectedVersion, String causation) {
    return new InspectionCommandMetadata(
        actor,
        role,
        new DomainVersion(expectedVersion),
        new CorrelationId("correlation-" + causation),
        new CausationId(causation),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static InspectionApplicationService newService() {
    return new InspectionApplicationService(new InMemoryInspectionProcessStore());
  }

  private static final class InMemoryInspectionProcessStore implements InspectionProcessStore {
    private final Map<InspectionProcessId, InspectionProcess> byId = new HashMap<>();

    @Override
    public Optional<InspectionProcess> findById(InspectionProcessId processId) {
      return Optional.ofNullable(byId.get(processId));
    }

    @Override
    public Optional<InspectionProcess> findByBusinessKey(
        InspectionProcess.InspectionBusinessKey businessKey) {
      return byId.values().stream()
          .filter(process -> process.businessKey().equals(businessKey))
          .findFirst();
    }

    @Override
    public InspectionProcess insertIfAbsent(InspectionProcess process) {
      InspectionProcess existing = byId.putIfAbsent(process.id(), process);
      return existing == null ? process : existing;
    }

    @Override
    public InspectionProcess commit(InspectionProcess process, DomainVersion expectedVersion) {
      InspectionProcess current = byId.get(process.id());
      if (current == null || !current.version().equals(expectedVersion))
        throw new ConflictException("Inspection process version conflict");
      byId.put(process.id(), process);
      return process;
    }
  }
}
