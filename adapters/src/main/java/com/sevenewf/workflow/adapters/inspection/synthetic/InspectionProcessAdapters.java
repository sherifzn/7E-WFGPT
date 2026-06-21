package com.sevenewf.workflow.adapters.inspection.synthetic;

import com.sevenewf.workflow.domain.common.ActorId;
import com.sevenewf.workflow.domain.common.CausationId;
import com.sevenewf.workflow.domain.common.CorrelationId;
import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.inspection.InspectionPendingEventStore;
import com.sevenewf.workflow.domain.inspection.InspectionProcess;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionAttempt;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionBusinessKey;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessId;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessStatus;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionResult;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTask;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTaskStatus;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionTaskType;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.RemediationCycle;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.RemediationStatus;
import com.sevenewf.workflow.domain.inspection.InspectionProcessStore;
import com.sevenewf.workflow.domain.inspection.PendingResumeEvent;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.EvidenceReference;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.KeyHandoverRequestId;
import com.sevenewf.workflow.domain.keyhandover.KeyHandoverTypes.PropertyReference;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;

public final class InspectionProcessAdapters {
  private InspectionProcessAdapters() {}

  public static class InMemoryInspectionProcessStore implements InspectionProcessStore {
    protected final Map<InspectionProcessId, InspectionProcess> byId = new HashMap<>();
    protected final Map<InspectionBusinessKey, InspectionProcessId> byBusinessKey = new HashMap<>();

    @Override
    public Optional<InspectionProcess> findById(InspectionProcessId processId) {
      return Optional.ofNullable(byId.get(processId));
    }

    @Override
    public Optional<InspectionProcess> findByBusinessKey(InspectionBusinessKey businessKey) {
      return Optional.ofNullable(byBusinessKey.get(businessKey)).flatMap(this::findById);
    }

    @Override
    public synchronized InspectionProcess insertIfAbsent(InspectionProcess process) {
      if (byBusinessKey.containsKey(process.businessKey()))
        return byId.get(byBusinessKey.get(process.businessKey()));
      byId.put(process.id(), process);
      byBusinessKey.put(process.businessKey(), process.id());
      afterMutation();
      return process;
    }

    @Override
    public synchronized InspectionProcess commit(
        InspectionProcess process, DomainVersion expectedVersion) {
      InspectionProcess current = byId.get(process.id());
      if (current == null || !current.version().equals(expectedVersion))
        throw new com.sevenewf.workflow.domain.inspection.InspectionApplicationExceptions
            .ConflictException("Inspection process version conflict");
      byId.put(process.id(), process);
      byBusinessKey.put(process.businessKey(), process.id());
      afterMutation();
      return process;
    }

    protected void afterMutation() {}
  }

  public static class InspectionProcessSnapshotStore extends InMemoryInspectionProcessStore
      implements InspectionPendingEventStore {
    private final Path location;
    private final List<PendingResumeEvent> pendingEvents = new ArrayList<>();

    public InspectionProcessSnapshotStore(Path location) {
      this.location = location.toAbsolutePath().normalize();
      load();
    }

    @Override
    public synchronized void appendPendingResumeEvent(PendingResumeEvent event) {
      pendingEvents.add(event);
      afterMutation();
    }

    @Override
    public synchronized List<PendingResumeEvent> pendingResumeEvents() {
      return List.copyOf(pendingEvents);
    }

    @Override
    public synchronized void markResumeEventHandled(PendingResumeEvent event) {
      int index = pendingEvents.indexOf(event);
      if (index >= 0) {
        pendingEvents.set(index, event.markHandled());
        afterMutation();
      }
    }

    @Override
    protected void afterMutation() {
      Path temporary = location.resolveSibling(location.getFileName() + ".tmp");
      try {
        Path parent = location.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (OutputStream output = Files.newOutputStream(temporary);
            DataOutputStream data = new DataOutputStream(output)) {
          InspectionProcessCodec.write(data, byId, byBusinessKey, pendingEvents);
        }
        try {
          Files.move(
              temporary,
              location,
              StandardCopyOption.ATOMIC_MOVE,
              StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
          Files.move(temporary, location, StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (IOException exception) {
        throw new IllegalStateException(
            "Unable to persist local inspection process snapshot", exception);
      }
    }

    private void load() {
      try {
        if (!Files.exists(location) || Files.size(location) == 0) return;
        try (InputStream input = Files.newInputStream(location);
            DataInputStream data = new DataInputStream(input)) {
          InspectionProcessCodec.read(data, byId, byBusinessKey, pendingEvents);
        }
      } catch (IOException exception) {
        throw new IllegalStateException(
            "Unable to load local inspection process snapshot", exception);
      }
    }
  }

  private static final class InspectionProcessCodec {
    private static final int FORMAT_VERSION = 1;

    private InspectionProcessCodec() {}

    static void write(
        DataOutputStream data,
        Map<InspectionProcessId, InspectionProcess> byId,
        Map<InspectionBusinessKey, InspectionProcessId> byBusinessKey,
        List<PendingResumeEvent> pendingEvents)
        throws IOException {
      data.writeInt(FORMAT_VERSION);
      data.writeInt(byId.size());
      for (InspectionProcess process : byId.values()) writeProcess(data, process);
      data.writeInt(byBusinessKey.size());
      for (Map.Entry<InspectionBusinessKey, InspectionProcessId> entry : byBusinessKey.entrySet()) {
        data.writeUTF(entry.getKey().value());
        data.writeUTF(entry.getValue().value());
      }
      data.writeInt(pendingEvents.size());
      for (PendingResumeEvent event : pendingEvents) writePendingEvent(data, event);
    }

    static void read(
        DataInputStream data,
        Map<InspectionProcessId, InspectionProcess> byId,
        Map<InspectionBusinessKey, InspectionProcessId> byBusinessKey,
        List<PendingResumeEvent> pendingEvents)
        throws IOException {
      int formatVersion = data.readInt();
      if (formatVersion < 1 || formatVersion > FORMAT_VERSION)
        throw new IOException("Unsupported inspection snapshot format");
      int processCount = data.readInt();
      for (int index = 0; index < processCount; index++) {
        InspectionProcess process = readProcess(data);
        byId.put(process.id(), process);
      }
      int indexCount = data.readInt();
      for (int index = 0; index < indexCount; index++)
        byBusinessKey.put(
            new InspectionBusinessKey(data.readUTF()), new InspectionProcessId(data.readUTF()));
      int eventCount = data.readInt();
      for (int index = 0; index < eventCount; index++) pendingEvents.add(readPendingEvent(data));
    }

    private static void writeProcess(DataOutputStream data, InspectionProcess process)
        throws IOException {
      data.writeUTF(process.id().value());
      data.writeUTF(process.businessKey().value());
      data.writeUTF(process.parentRequestId().value());
      data.writeUTF(process.propertyReference().value());
      data.writeUTF(process.inspectionType());
      data.writeUTF(process.status().name());
      data.writeInt(process.version().value());
      writeAttempts(data, process.attempts());
      writeRemediationCycles(data, process.remediationCycles());
      writeTasks(data, process.tasks());
      data.writeBoolean(process.cancellationReason().isPresent());
      if (process.cancellationReason().isPresent())
        data.writeUTF(process.cancellationReason().orElseThrow());
      data.writeUTF(process.correlationId().value());
      data.writeUTF(process.causationId().value());
      writeInstant(data, process.updatedAt());
    }

    private static InspectionProcess readProcess(DataInputStream data) throws IOException {
      InspectionProcessId id = new InspectionProcessId(data.readUTF());
      InspectionBusinessKey businessKey = new InspectionBusinessKey(data.readUTF());
      KeyHandoverRequestId parentRequestId = new KeyHandoverRequestId(data.readUTF());
      PropertyReference propertyReference = new PropertyReference(data.readUTF());
      String inspectionType = data.readUTF();
      InspectionProcessStatus status = InspectionProcessStatus.valueOf(data.readUTF());
      DomainVersion version = new DomainVersion(data.readInt());
      List<InspectionAttempt> attempts = readAttempts(data);
      List<RemediationCycle> remediationCycles = readRemediationCycles(data);
      List<InspectionTask> tasks = readTasks(data);
      Optional<String> cancellationReason =
          data.readBoolean() ? Optional.of(data.readUTF()) : Optional.empty();
      CorrelationId correlationId = new CorrelationId(data.readUTF());
      CausationId causationId = new CausationId(data.readUTF());
      Instant updatedAt = readInstant(data);
      return new InspectionProcess(
          id,
          businessKey,
          parentRequestId,
          propertyReference,
          inspectionType,
          status,
          version,
          attempts,
          remediationCycles,
          tasks,
          cancellationReason,
          correlationId,
          causationId,
          updatedAt);
    }

    private static void writeAttempts(DataOutputStream data, List<InspectionAttempt> attempts)
        throws IOException {
      data.writeInt(attempts.size());
      for (InspectionAttempt attempt : attempts) {
        data.writeInt(attempt.number());
        data.writeUTF(attempt.result().name());
        data.writeUTF(attempt.findings());
        data.writeUTF(attempt.evidenceReference().value());
        writeInstant(data, attempt.completedAt());
        data.writeBoolean(attempt.validUntil().isPresent());
        if (attempt.validUntil().isPresent())
          writeInstant(data, attempt.validUntil().orElseThrow());
      }
    }

    private static List<InspectionAttempt> readAttempts(DataInputStream data) throws IOException {
      int count = data.readInt();
      List<InspectionAttempt> attempts = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        int number = data.readInt();
        InspectionResult result = InspectionResult.valueOf(data.readUTF());
        String findings = data.readUTF();
        EvidenceReference evidenceReference = new EvidenceReference(data.readUTF());
        Instant completedAt = readInstant(data);
        Optional<Instant> validUntil =
            data.readBoolean() ? Optional.of(readInstant(data)) : Optional.empty();
        attempts.add(
            new InspectionAttempt(
                number, result, findings, evidenceReference, completedAt, validUntil));
      }
      return List.copyOf(attempts);
    }

    private static void writeRemediationCycles(DataOutputStream data, List<RemediationCycle> cycles)
        throws IOException {
      data.writeInt(cycles.size());
      for (RemediationCycle cycle : cycles) {
        data.writeInt(cycle.number());
        data.writeUTF(cycle.status().name());
        data.writeBoolean(cycle.resolutionSummary().isPresent());
        if (cycle.resolutionSummary().isPresent())
          data.writeUTF(cycle.resolutionSummary().orElseThrow());
        data.writeBoolean(cycle.remediationReference().isPresent());
        if (cycle.remediationReference().isPresent())
          data.writeUTF(cycle.remediationReference().orElseThrow().value());
      }
    }

    private static List<RemediationCycle> readRemediationCycles(DataInputStream data)
        throws IOException {
      int count = data.readInt();
      List<RemediationCycle> cycles = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        int number = data.readInt();
        RemediationStatus status = RemediationStatus.valueOf(data.readUTF());
        Optional<String> resolutionSummary =
            data.readBoolean() ? Optional.of(data.readUTF()) : Optional.empty();
        Optional<EvidenceReference> remediationReference =
            data.readBoolean()
                ? Optional.of(new EvidenceReference(data.readUTF()))
                : Optional.empty();
        cycles.add(new RemediationCycle(number, status, resolutionSummary, remediationReference));
      }
      return List.copyOf(cycles);
    }

    private static void writeTasks(DataOutputStream data, List<InspectionTask> tasks)
        throws IOException {
      data.writeInt(tasks.size());
      for (InspectionTask task : tasks) {
        data.writeUTF(task.id());
        data.writeUTF(task.type().name());
        data.writeUTF(task.status().name());
        data.writeUTF(task.requiredRole().name());
        data.writeBoolean(task.assignee().isPresent());
        if (task.assignee().isPresent()) data.writeUTF(task.assignee().orElseThrow().value());
        writeInstant(data, task.createdAt());
        data.writeBoolean(task.claimedAt().isPresent());
        if (task.claimedAt().isPresent()) writeInstant(data, task.claimedAt().orElseThrow());
        data.writeBoolean(task.completedAt().isPresent());
        if (task.completedAt().isPresent()) writeInstant(data, task.completedAt().orElseThrow());
        data.writeBoolean(task.outcome().isPresent());
        if (task.outcome().isPresent()) data.writeUTF(task.outcome().orElseThrow());
        data.writeInt(task.version().value());
        data.writeUTF(task.correlationId().value());
        data.writeUTF(task.causationId().value());
      }
    }

    private static List<InspectionTask> readTasks(DataInputStream data) throws IOException {
      int count = data.readInt();
      List<InspectionTask> tasks = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        String id = data.readUTF();
        InspectionTaskType type = InspectionTaskType.valueOf(data.readUTF());
        InspectionTaskStatus status = InspectionTaskStatus.valueOf(data.readUTF());
        InspectionProcess.InspectionRole requiredRole =
            InspectionProcess.InspectionRole.valueOf(data.readUTF());
        Optional<ActorId> assignee =
            data.readBoolean() ? Optional.of(new ActorId(data.readUTF())) : Optional.empty();
        Instant createdAt = readInstant(data);
        Optional<Instant> claimedAt =
            data.readBoolean() ? Optional.of(readInstant(data)) : Optional.empty();
        Optional<Instant> completedAt =
            data.readBoolean() ? Optional.of(readInstant(data)) : Optional.empty();
        Optional<String> outcome =
            data.readBoolean() ? Optional.of(data.readUTF()) : Optional.empty();
        DomainVersion version = new DomainVersion(data.readInt());
        CorrelationId correlationId = new CorrelationId(data.readUTF());
        CausationId causationId = new CausationId(data.readUTF());
        tasks.add(
            new InspectionTask(
                id,
                type,
                status,
                requiredRole,
                assignee,
                createdAt,
                claimedAt,
                completedAt,
                outcome,
                version,
                correlationId,
                causationId));
      }
      return List.copyOf(tasks);
    }

    private static void writePendingEvent(DataOutputStream data, PendingResumeEvent event)
        throws IOException {
      data.writeUTF(event.processId().value());
      data.writeInt(event.passedAttemptNumber());
      data.writeUTF(event.evidenceReference().value());
      data.writeBoolean(event.handled());
      writeInstant(data, event.createdAt());
    }

    private static PendingResumeEvent readPendingEvent(DataInputStream data) throws IOException {
      InspectionProcessId processId = new InspectionProcessId(data.readUTF());
      int passedAttemptNumber = data.readInt();
      EvidenceReference evidenceReference = new EvidenceReference(data.readUTF());
      boolean handled = data.readBoolean();
      Instant createdAt = readInstant(data);
      return new PendingResumeEvent(
          processId, passedAttemptNumber, evidenceReference, handled, createdAt);
    }

    private static void writeInstant(DataOutputStream data, Instant instant) throws IOException {
      data.writeLong(instant.getEpochSecond());
      data.writeInt(instant.getNano());
    }

    private static Instant readInstant(DataInputStream data) throws IOException {
      return Instant.ofEpochSecond(data.readLong(), data.readInt());
    }
  }
}
