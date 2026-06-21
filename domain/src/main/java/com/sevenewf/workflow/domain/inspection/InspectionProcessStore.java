package com.sevenewf.workflow.domain.inspection;

import com.sevenewf.workflow.domain.common.DomainVersion;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionBusinessKey;
import com.sevenewf.workflow.domain.inspection.InspectionProcess.InspectionProcessId;
import java.util.Optional;

public interface InspectionProcessStore {
  Optional<InspectionProcess> findById(InspectionProcessId processId);

  Optional<InspectionProcess> findByBusinessKey(InspectionBusinessKey businessKey);

  InspectionProcess insertIfAbsent(InspectionProcess process);

  InspectionProcess commit(InspectionProcess process, DomainVersion expectedVersion);
}
