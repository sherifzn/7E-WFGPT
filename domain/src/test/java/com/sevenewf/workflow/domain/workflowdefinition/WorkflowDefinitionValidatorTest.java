package com.sevenewf.workflow.domain.workflowdefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class WorkflowDefinitionValidatorTest {

  @Test
  void simpleLinearWorkflowIsValid() {
    ValidationResult result =
        WorkflowDefinitionValidator.validate(WorkflowDefinitionFixtures.simpleLinearWorkflow());
    assertTrue(result.isValid(), result.findings().toString());
  }

  @Test
  void parallelWorkflowIsValid() {
    ValidationResult result =
        WorkflowDefinitionValidator.validate(WorkflowDefinitionFixtures.parallelWorkflow());
    assertTrue(result.isValid(), result.findings().toString());
  }

  @Test
  void childWorkflowCorrelationIsValid() {
    ValidationResult result =
        WorkflowDefinitionValidator.validate(WorkflowDefinitionFixtures.childWorkflow());
    assertTrue(result.isValid(), result.findings().toString());
  }

  @Test
  void waitEventWorkflowIsValid() {
    ValidationResult result =
        WorkflowDefinitionValidator.validate(WorkflowDefinitionFixtures.waitEventWorkflow());
    assertTrue(result.isValid(), result.findings().toString());
  }

  @Test
  void timerWorkflowIsValid() {
    ValidationResult result =
        WorkflowDefinitionValidator.validate(WorkflowDefinitionFixtures.timerWorkflow());
    assertTrue(result.isValid(), result.findings().toString());
  }

  @Test
  void simplifiedKeyHandoverFixtureIsValid() {
    ValidationResult result =
        WorkflowDefinitionValidator.validate(WorkflowDefinitionFixtures.simplifiedKeyHandover());
    assertTrue(result.isValid(), result.findings().toString());
  }

  @Test
  void publishedDefinitionIsValidAndImmutable() {
    WorkflowDefinition definition = WorkflowDefinitionFixtures.publishedDefinition();
    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertTrue(result.isValid(), result.findings().toString());
    assertEquals(WorkflowDefinitionStatus.PUBLISHED, definition.status());
    assertThrows(UnsupportedOperationException.class, () -> definition.activities().add(null));
    assertThrows(UnsupportedOperationException.class, () -> definition.transitions().add(null));
    assertThrows(
        UnsupportedOperationException.class, () -> definition.terminalActivityKeys().add("x"));
    assertThrows(UnsupportedOperationException.class, () -> definition.metadata().put("x", "y"));
  }

  @Test
  void missingStartIsInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    EndActivity end = new EndActivity("end", "End", "", null, null, null, null, null, Map.of());
    WorkflowDefinition definition =
        new WorkflowDefinition(
            base.workflowKey(),
            base.displayName(),
            base.description(),
            base.version(),
            base.status(),
            List.of(end),
            List.of(),
            "end",
            List.of("end"),
            Map.of(),
            Instant.now(),
            "tester",
            "correlationKey",
            "commandId");

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_NO_START");
  }

  @Test
  void multipleStartsAreInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    StartActivity extraStart =
        new StartActivity("start2", "Start 2", "", null, null, null, null, null, Map.of());
    List<ActivityDefinition> activities = new ArrayList<>(base.activities());
    activities.add(extraStart);
    WorkflowDefinition definition =
        new WorkflowDefinition(
            base.workflowKey(),
            base.displayName(),
            base.description(),
            base.version(),
            base.status(),
            activities,
            base.transitions(),
            base.startActivityKey(),
            base.terminalActivityKeys(),
            Map.of(),
            Instant.now(),
            "tester",
            "correlationKey",
            "commandId");

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_MULTIPLE_STARTS");
  }

  @Test
  void missingEndIsInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    StartActivity start =
        new StartActivity("start", "Start", "", null, null, null, null, null, Map.of());
    WorkflowDefinition definition =
        new WorkflowDefinition(
            base.workflowKey(),
            base.displayName(),
            base.description(),
            base.version(),
            base.status(),
            List.of(start),
            List.of(),
            "start",
            List.of("start"),
            Map.of(),
            Instant.now(),
            "tester",
            "correlationKey",
            "commandId");

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_NO_END");
  }

  @Test
  void duplicateActivityKeysAreInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    HumanTaskActivity duplicate =
        new HumanTaskActivity(
            "task",
            "Duplicate Task",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "taskType",
            "operator",
            List.of("DONE"),
            List.of(),
            null,
            null);
    List<ActivityDefinition> activities = new ArrayList<>(base.activities());
    activities.add(duplicate);
    WorkflowDefinition definition = copyWithActivities(base, activities);

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_DUPLICATE_ACTIVITY_KEY");
  }

  @Test
  void unknownTransitionTargetsAreInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    WorkflowDefinition definition =
        copyWithTransitions(
            base,
            List.of(
                new Transition("start", "unknown", null, null, 0, ""),
                new Transition("unknown", "end", null, null, 0, "")));

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_UNKNOWN_TRANSITION_SOURCE");
    assertHasCode(result, "WF_UNKNOWN_TRANSITION_TARGET");
  }

  @Test
  void unreachableActivityIsInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    HumanTaskActivity orphan =
        new HumanTaskActivity(
            "orphan",
            "Orphan",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "taskType",
            "operator",
            List.of("DONE"),
            List.of(),
            null,
            null);
    List<ActivityDefinition> activities = new ArrayList<>(base.activities());
    activities.add(orphan);
    WorkflowDefinition definition = copyWithActivities(base, activities);

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_UNREACHABLE_ACTIVITY");
  }

  @Test
  void terminalActivityWithOutgoingTransitionIsInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    WorkflowDefinition definition =
        copyWithTransitions(
            base,
            List.of(
                new Transition("start", "task", null, null, 0, ""),
                new Transition("task", "end", null, null, 0, ""),
                new Transition("end", "start", null, null, 0, "")));

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_TERMINAL_HAS_OUTGOING");
  }

  @Test
  void missingOutgoingTransitionIsInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    WorkflowDefinition definition =
        copyWithTransitions(base, List.of(new Transition("start", "task", null, null, 0, "")));

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_MISSING_OUTGOING_TRANSITION");
  }

  @Test
  void invalidParallelSplitIsInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    ParallelSplitActivity split =
        new ParallelSplitActivity("split", "Split", "", null, null, null, null, null, Map.of());
    WorkflowDefinition definition =
        new WorkflowDefinition(
            base.workflowKey(),
            base.displayName(),
            base.description(),
            base.version(),
            base.status(),
            List.of(
                new StartActivity("start", "Start", "", null, null, null, null, null, Map.of()),
                split,
                new EndActivity("end", "End", "", null, null, null, null, null, Map.of())),
            List.of(
                new Transition("start", "split", null, null, 0, ""),
                new Transition("split", "end", null, null, 0, "")),
            "start",
            List.of("end"),
            Map.of(),
            Instant.now(),
            "tester",
            "correlationKey",
            "commandId");

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_PARALLEL_SPLIT_BRANCHES");
  }

  @Test
  void orphanParallelJoinIsInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    ParallelJoinActivity join =
        new ParallelJoinActivity("join", "Join", "", null, null, null, null, null, Map.of());
    HumanTaskActivity a =
        new HumanTaskActivity(
            "a",
            "A",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "taskType",
            "operator",
            List.of("DONE"),
            List.of(),
            null,
            null);
    HumanTaskActivity b =
        new HumanTaskActivity(
            "b",
            "B",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "taskType",
            "operator",
            List.of("DONE"),
            List.of(),
            null,
            null);
    WorkflowDefinition definition =
        new WorkflowDefinition(
            base.workflowKey(),
            base.displayName(),
            base.description(),
            base.version(),
            base.status(),
            List.of(
                new StartActivity("start", "Start", "", null, null, null, null, null, Map.of()),
                a,
                b,
                join,
                new EndActivity("end", "End", "", null, null, null, null, null, Map.of())),
            List.of(
                new Transition("start", "a", null, null, 0, ""),
                new Transition("start", "b", null, null, 0, ""),
                new Transition("a", "join", null, null, 0, ""),
                new Transition("b", "join", null, null, 0, ""),
                new Transition("join", "end", null, null, 0, "")),
            "start",
            List.of("end"),
            Map.of(),
            Instant.now(),
            "tester",
            "correlationKey",
            "commandId");

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_ORPHAN_JOIN");
  }

  @Test
  void invalidDecisionOutcomesAreInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    DecisionActivity noOutcomes =
        new DecisionActivity(
            "decision",
            "Decision",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "decision",
            "rule-set",
            List.of());
    WorkflowDefinition definition =
        new WorkflowDefinition(
            base.workflowKey(),
            base.displayName(),
            base.description(),
            base.version(),
            base.status(),
            List.of(
                new StartActivity("start", "Start", "", null, null, null, null, null, Map.of()),
                noOutcomes,
                new EndActivity("end", "End", "", null, null, null, null, null, Map.of())),
            List.of(
                new Transition("start", "decision", null, null, 0, ""),
                new Transition("decision", "end", null, null, 0, "")),
            "start",
            List.of("end"),
            Map.of(),
            Instant.now(),
            "tester",
            "correlationKey",
            "commandId");

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_DECISION_NO_OUTCOMES");

    DecisionActivity duplicateOutcomes =
        new DecisionActivity(
            "decision",
            "Decision",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "decision",
            "rule-set",
            List.of("YES", "YES"));
    WorkflowDefinition duplicateDefinition =
        copyWithActivities(
            definition,
            List.of(
                new StartActivity("start", "Start", "", null, null, null, null, null, Map.of()),
                duplicateOutcomes,
                new EndActivity("end", "End", "", null, null, null, null, null, Map.of())));
    ValidationResult duplicateResult = WorkflowDefinitionValidator.validate(duplicateDefinition);
    assertFalse(duplicateResult.isValid());
    assertHasCode(duplicateResult, "WF_DECISION_DUPLICATE_OUTCOME");
  }

  @Test
  void humanTaskWithoutRoleIsInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    HumanTaskActivity noRole =
        new HumanTaskActivity(
            "task",
            "Task",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "taskType",
            "",
            List.of("DONE"),
            List.of(),
            null,
            null);
    WorkflowDefinition definition =
        copyWithActivities(
            base,
            List.of(
                new StartActivity("start", "Start", "", null, null, null, null, null, Map.of()),
                noRole,
                new EndActivity("end", "End", "", null, null, null, null, null, Map.of())));

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_HUMAN_TASK_NO_ROLE");
  }

  @Test
  void humanTaskWithoutOutcomesIsInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    HumanTaskActivity noOutcomes =
        new HumanTaskActivity(
            "task",
            "Task",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "taskType",
            "operator",
            List.of(),
            List.of(),
            null,
            null);
    WorkflowDefinition definition =
        copyWithActivities(
            base,
            List.of(
                new StartActivity("start", "Start", "", null, null, null, null, null, Map.of()),
                noOutcomes,
                new EndActivity("end", "End", "", null, null, null, null, null, Map.of())));

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_HUMAN_TASK_NO_OUTCOMES");
  }

  @Test
  void childWorkflowWithoutCorrelationMappingIsInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.childWorkflow();
    ChildWorkflowActivity noCorrelation =
        new ChildWorkflowActivity(
            "child",
            "Child",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "inspection",
            null,
            "",
            "",
            DuplicatePolicy.CREATE_NEW,
            null);
    WorkflowDefinition definition =
        copyWithActivities(
            base,
            List.of(
                new StartActivity("start", "Start", "", null, null, null, null, null, Map.of()),
                noCorrelation,
                new EndActivity("end", "End", "", null, null, null, null, null, Map.of())));

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_CHILD_NO_CORRELATION");
    assertHasCode(result, "WF_CHILD_NO_BUSINESS_KEY");
  }

  @Test
  void invalidTimerIsInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.timerWorkflow();
    TimerActivity noDuration =
        new TimerActivity(
            "timer", "Timer", "", null, null, null, null, null, Map.of(), null, null, null);
    WorkflowDefinition definition =
        copyWithActivities(
            base,
            List.of(
                new StartActivity("start", "Start", "", null, null, null, null, null, Map.of()),
                noDuration,
                new EndActivity("end", "End", "", null, null, null, null, null, Map.of())));

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_TIMER_NO_DURATION");
  }

  @Test
  void invalidWaitEventIsInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.waitEventWorkflow();
    WaitEventActivity noEventType =
        new WaitEventActivity(
            "wait", "Wait", "", null, null, null, null, null, Map.of(), "", null, null, null);
    WorkflowDefinition definition =
        copyWithActivities(
            base,
            List.of(
                new StartActivity("start", "Start", "", null, null, null, null, null, Map.of()),
                noEventType,
                new EndActivity("end", "End", "", null, null, null, null, null, Map.of())));

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_WAIT_NO_EVENT_TYPE");
  }

  @Test
  void undeclaredCycleIsInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    WorkflowDefinition definition =
        copyWithTransitions(
            base,
            List.of(
                new Transition("start", "task", null, null, 0, ""),
                new Transition("task", "end", null, null, 0, ""),
                new Transition("end", "task", null, null, 0, "")));

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_UNDECLARED_CYCLE");
  }

  @Test
  void declaredLoopPolicyAllowsCycles() {
    DecisionActivity decision =
        new DecisionActivity(
            "decision",
            "Decision",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "decision",
            "rule-set",
            List.of("RETRY", "PROCEED"));
    HumanTaskActivity task =
        new HumanTaskActivity(
            "task",
            "Task",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "taskType",
            "operator",
            List.of("DONE"),
            List.of(),
            null,
            null);
    EndActivity end = new EndActivity("end", "End", "", null, null, null, null, null, Map.of());
    WorkflowDefinition definition =
        new WorkflowDefinition(
            "loop.workflow",
            "Loop Workflow",
            "",
            new SemanticVersion("1.0.0"),
            WorkflowDefinitionStatus.DRAFT,
            List.of(
                new StartActivity("start", "Start", "", null, null, null, null, null, Map.of()),
                task,
                decision,
                end),
            List.of(
                new Transition("start", "task", null, null, 0, ""),
                new Transition("task", "decision", null, null, 0, ""),
                new Transition("decision", "task", "RETRY", null, 0, ""),
                new Transition("decision", "end", "PROCEED", null, 0, "")),
            "start",
            List.of("end"),
            Map.of("loopPolicy", "explicit"),
            Instant.now(),
            "tester",
            "correlationKey",
            "commandId");

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertTrue(result.isValid(), result.findings().toString());
  }

  @Test
  void conflictingTransitionPriorityIsInvalid() {
    WorkflowDefinition base = WorkflowDefinitionFixtures.simpleLinearWorkflow();
    WorkflowDefinition definition =
        new WorkflowDefinition(
            base.workflowKey(),
            base.displayName(),
            base.description(),
            base.version(),
            base.status(),
            List.of(
                new StartActivity("start", "Start", "", null, null, null, null, null, Map.of()),
                new HumanTaskActivity(
                    "task",
                    "Task",
                    "",
                    null,
                    null,
                    null,
                    null,
                    null,
                    Map.of(),
                    "taskType",
                    "operator",
                    List.of("DONE"),
                    List.of(),
                    null,
                    null),
                new EndActivity("end", "End", "", null, null, null, null, null, Map.of())),
            List.of(
                new Transition("start", "task", null, null, 0, ""),
                new Transition("task", "end", null, null, 0, "first"),
                new Transition("task", "end", null, null, 0, "second")),
            "start",
            List.of("end"),
            Map.of(),
            Instant.now(),
            "tester",
            "correlationKey",
            "commandId");

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_CONFLICTING_TRANSITION_PRIORITY");
  }

  @Test
  void unknownActivityTypeDuringDeserializationIsRejected() {
    String json =
        "{"
            + "\"workflowKey\": \"bad\","
            + "\"displayName\": \"Bad\","
            + "\"description\": \"\","
            + "\"version\": \"1.0.0\","
            + "\"status\": \"DRAFT\","
            + "\"activities\": [{\"type\": \"UNKNOWN_TYPE\", \"activityKey\": \"x\", \"displayName\": \"X\"}],"
            + "\"transitions\": [],"
            + "\"startActivityKey\": \"x\","
            + "\"terminalActivityKeys\": [\"x\"],"
            + "\"metadata\": {},"
            + "\"createdAt\": \"2026-01-01T00:00:00Z\","
            + "\"createdBy\": \"tester\","
            + "\"correlationKeyDefinition\": \"k\","
            + "\"idempotencyKeyDefinition\": \"i\""
            + "}";

    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class, () -> WorkflowDefinitionJson.deserialize(json));
    assertTrue(exception.getMessage().contains("Unknown activity type"));
  }

  @Test
  void jsonRoundTripPreservesDefinition() {
    WorkflowDefinition original = WorkflowDefinitionFixtures.simplifiedKeyHandover();
    String json = WorkflowDefinitionJson.serialize(original);
    WorkflowDefinition roundTripped = WorkflowDefinitionJson.deserialize(json);

    assertEquals(original.workflowKey(), roundTripped.workflowKey());
    assertEquals(original.version(), roundTripped.version());
    assertEquals(original.status(), roundTripped.status());
    assertEquals(original.activities().size(), roundTripped.activities().size());
    assertEquals(original.transitions().size(), roundTripped.transitions().size());
    assertEquals(original.startActivityKey(), roundTripped.startActivityKey());
    assertEquals(original.terminalActivityKeys(), roundTripped.terminalActivityKeys());
    assertTrue(WorkflowDefinitionValidator.validate(roundTripped).isValid());
  }

  @Test
  void semanticVersionRejectsInvalidValues() {
    assertThrows(IllegalArgumentException.class, () -> new SemanticVersion(""));
    assertThrows(IllegalArgumentException.class, () -> new SemanticVersion("1.0"));
    assertThrows(IllegalArgumentException.class, () -> new SemanticVersion("01.0.0"));
    assertThrows(IllegalArgumentException.class, () -> new SemanticVersion("1.0.0-"));
  }

  private static WorkflowDefinition copyWithActivities(
      WorkflowDefinition source, List<ActivityDefinition> activities) {
    return new WorkflowDefinition(
        source.workflowKey(),
        source.displayName(),
        source.description(),
        source.version(),
        source.status(),
        activities,
        source.transitions(),
        source.startActivityKey(),
        source.terminalActivityKeys(),
        source.metadata(),
        Instant.now(),
        source.createdBy(),
        source.correlationKeyDefinition(),
        source.idempotencyKeyDefinition());
  }

  private static WorkflowDefinition copyWithTransitions(
      WorkflowDefinition source, List<Transition> transitions) {
    return new WorkflowDefinition(
        source.workflowKey(),
        source.displayName(),
        source.description(),
        source.version(),
        source.status(),
        source.activities(),
        transitions,
        source.startActivityKey(),
        source.terminalActivityKeys(),
        source.metadata(),
        Instant.now(),
        source.createdBy(),
        source.correlationKeyDefinition(),
        source.idempotencyKeyDefinition());
  }

  private static void assertHasCode(ValidationResult result, String code) {
    boolean found = result.findings().stream().anyMatch(f -> f.code().equals(code));
    assertTrue(found, "Expected finding code " + code + " in " + result.findings());
  }
}
