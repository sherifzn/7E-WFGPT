package com.sevenewf.workflow.adapters.serialization;

import com.sevenewf.workflow.domain.audit.AuditEvent;
import com.sevenewf.workflow.domain.workflow.WorkflowDefinition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

final class DomainSchemaCatalogTest {
  @Test
  void exposesSchemasWithoutAddingSerializationAnnotationsToDomain() {
    Assertions.assertTrue(DomainSchemaCatalog.schemaFor(WorkflowDefinition.class).isPresent());
    Assertions.assertTrue(DomainSchemaCatalog.schemaFor(AuditEvent.class).isPresent());
  }
}
