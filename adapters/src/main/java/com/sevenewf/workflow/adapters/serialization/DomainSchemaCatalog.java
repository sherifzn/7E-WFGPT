package com.sevenewf.workflow.adapters.serialization;

import com.sevenewf.workflow.domain.audit.AuditEvent;
import com.sevenewf.workflow.domain.work.TaskInstance;
import com.sevenewf.workflow.domain.workflow.ActivityInstance;
import com.sevenewf.workflow.domain.workflow.WorkflowDefinition;
import com.sevenewf.workflow.domain.workflow.WorkflowInstance;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

public final class DomainSchemaCatalog {
  private static final Map<Class<?>, String> SCHEMAS =
      Map.of(
          WorkflowDefinition.class,
          "/schemas/workflow-definition.schema.json",
          WorkflowInstance.class,
          "/schemas/workflow-instance.schema.json",
          ActivityInstance.class,
          "/schemas/activity-instance.schema.json",
          TaskInstance.class,
          "/schemas/task-instance.schema.json",
          AuditEvent.class,
          "/schemas/audit-event.schema.json");

  private DomainSchemaCatalog() {}

  public static Optional<String> schemaPathFor(Class<?> contractType) {
    return Optional.ofNullable(SCHEMAS.get(contractType));
  }

  public static Optional<InputStream> schemaFor(Class<?> contractType) {
    return schemaPathFor(contractType).map(DomainSchemaCatalog.class::getResourceAsStream);
  }
}
