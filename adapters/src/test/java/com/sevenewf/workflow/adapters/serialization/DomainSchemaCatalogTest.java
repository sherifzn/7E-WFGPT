package com.sevenewf.workflow.adapters.serialization;

import com.sevenewf.workflow.domain.audit.AuditEvent;
import com.sevenewf.workflow.domain.common.DataClassification;
import com.sevenewf.workflow.domain.work.TaskInstance;
import com.sevenewf.workflow.domain.workflow.ActivityInstance;
import com.sevenewf.workflow.domain.workflow.WorkflowDefinition;
import com.sevenewf.workflow.domain.workflow.WorkflowInstance;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

final class DomainSchemaCatalogTest {
  @Test
  void exposesNonEmptySchemasWithoutAddingSerializationAnnotationsToDomain() throws IOException {
    for (Class<?> contractType : requiredFieldsByContract().keySet()) {
      String schema = schemaText(contractType);

      Assertions.assertFalse(schema.isBlank(), contractType.getSimpleName() + " schema is empty");
      requiredFieldsByContract()
          .get(contractType)
          .forEach(fieldName -> assertRequiredField(schema, fieldName));
      assertCanonicalDataClassificationValues(schema);
    }
  }

  @Test
  void runtimeInstanceSchemasRepresentMandatoryStateVersions() throws IOException {
    for (Class<?> contractType :
        List.of(WorkflowInstance.class, ActivityInstance.class, TaskInstance.class)) {
      String schema = schemaText(contractType);

      assertRequiredField(schema, "stateVersion");
      Assertions.assertTrue(schema.contains("\"minimum\": 1"));
    }
  }

  @Test
  void auditEventSchemaRepresentsEnvelopeRequiredFields() throws IOException {
    String schema = schemaText(AuditEvent.class);

    for (String requiredField :
        List.of(
            "eventId",
            "eventType",
            "eventVersion",
            "occurredAt",
            "correlationId",
            "causationId",
            "actorType",
            "actorId",
            "tenantScope",
            "dataClassification",
            "policyVersions",
            "traceId")) {
      assertRequiredField(schema, requiredField);
    }
  }

  private static Map<Class<?>, List<String>> requiredFieldsByContract() {
    return Map.of(
        WorkflowDefinition.class,
        List.of(
            "id", "definitionVersion", "key", "displayName", "dataClassification", "activities"),
        WorkflowInstance.class,
        List.of(
            "id",
            "stateVersion",
            "workflowVersionId",
            "status",
            "tenantScope",
            "correlationId",
            "createdAt",
            "dataClassification"),
        ActivityInstance.class,
        List.of(
            "id",
            "stateVersion",
            "workflowInstanceId",
            "activityDefinitionId",
            "status",
            "createdAt",
            "dataClassification"),
        TaskInstance.class,
        List.of(
            "id",
            "stateVersion",
            "taskTypeId",
            "activityInstanceId",
            "status",
            "createdAt",
            "dataClassification"),
        AuditEvent.class,
        List.of(
            "eventId",
            "eventType",
            "eventVersion",
            "occurredAt",
            "correlationId",
            "causationId",
            "actorType",
            "actorId",
            "tenantScope",
            "dataClassification",
            "policyVersions",
            "traceId"));
  }

  private static String schemaText(Class<?> contractType) throws IOException {
    try (var schema = DomainSchemaCatalog.schemaFor(contractType).orElseThrow()) {
      return new String(schema.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void assertRequiredField(String schema, String fieldName) {
    Assertions.assertTrue(
        schema.contains("\"" + fieldName + "\""), "Schema missing required field: " + fieldName);
  }

  private static void assertCanonicalDataClassificationValues(String schema) {
    String dataClassificationEnum = enumBlockAfter(schema, "\"dataClassification\": {");
    List<String> enumValues =
        Pattern.compile("\"([A-Z_]+)\"")
            .matcher(dataClassificationEnum)
            .results()
            .map(match -> match.group(1))
            .toList();

    Assertions.assertEquals(
        Arrays.stream(DataClassification.values()).map(Enum::name).toList(), enumValues);
  }

  private static String enumBlockAfter(String schema, String marker) {
    int markerIndex = schema.indexOf(marker);
    int enumIndex = schema.indexOf("\"enum\"", markerIndex);
    int startIndex = schema.indexOf('[', enumIndex);
    int endIndex = schema.indexOf(']', startIndex);
    Assertions.assertTrue(
        markerIndex >= 0 && enumIndex >= 0 && startIndex >= 0 && endIndex > startIndex);
    return schema.substring(startIndex, endIndex + 1);
  }
}
