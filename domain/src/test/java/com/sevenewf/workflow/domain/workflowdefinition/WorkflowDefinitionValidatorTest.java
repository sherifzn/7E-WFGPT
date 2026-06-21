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
        new ParallelSplitActivity(
            "split", "Split", "", null, null, null, null, null, Map.of(), "pair-1");
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
        new ParallelJoinActivity(
            "join", "Join", "", null, null, null, null, null, Map.of(), "pair-1");
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
                new Transition(
                    "decision",
                    "task",
                    "RETRY",
                    null,
                    0,
                    "",
                    new LoopPolicy("retry-loop", LoopType.BOUNDED, 3, null, null, null)),
                new Transition("decision", "end", "PROCEED", null, 0, "")),
            "start",
            List.of("end"),
            Map.of(),
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

  @Test
  void boundedLoopIsAccepted() {
    WorkflowDefinition definition = loopWorkflow();
    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertTrue(result.isValid(), result.findings().toString());
  }

  @Test
  void boundedLoopWithoutMaximumIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new LoopPolicy("retry-loop", LoopType.BOUNDED, null, null, null, null));
  }

  @Test
  void conditionLoopWithoutExitConditionIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new LoopPolicy("retry-loop", LoopType.CONDITION_CONTROLLED, null, null, null, null));
  }

  @Test
  void policyLoopWithoutPolicyReferenceIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new LoopPolicy("retry-loop", LoopType.POLICY_CONTROLLED, null, null, null, null));
  }

  @Test
  void cycleWithoutLoopPolicyIsRejected() {
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
  void unrelatedLoopPolicyDoesNotAuthorizeAnotherCycle() {
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
    HumanTaskActivity extra =
        new HumanTaskActivity(
            "extra",
            "Extra",
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
            "multi.loop",
            "Multi Loop",
            "",
            new SemanticVersion("1.0.0"),
            WorkflowDefinitionStatus.DRAFT,
            List.of(
                new StartActivity("start", "Start", "", null, null, null, null, null, Map.of()),
                task,
                extra,
                decision,
                end),
            List.of(
                new Transition("start", "task", null, null, 0, ""),
                new Transition("task", "decision", null, null, 0, ""),
                new Transition(
                    "decision",
                    "task",
                    "RETRY",
                    null,
                    0,
                    "",
                    new LoopPolicy("retry-loop", LoopType.BOUNDED, 3, null, null, null)),
                new Transition("decision", "extra", "PROCEED", null, 0, ""),
                new Transition("extra", "task", null, null, 0, "")),
            "start",
            List.of("end"),
            Map.of(),
            Instant.now(),
            "tester",
            "correlationKey",
            "commandId");

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_UNDECLARED_CYCLE");
  }

  @Test
  void loopPolicyJsonRoundTrip() {
    WorkflowDefinition original = loopWorkflow();
    String json = WorkflowDefinitionJson.serialize(original);
    WorkflowDefinition roundTripped = WorkflowDefinitionJson.deserialize(json);

    Transition originalBackEdge = roundTripped.transitions().get(2);
    assertEquals("decision", originalBackEdge.sourceActivityKey());
    assertEquals("task", originalBackEdge.targetActivityKey());
    assertEquals("retry-loop", originalBackEdge.loopPolicy().loopPolicyKey());
    assertEquals(LoopType.BOUNDED, originalBackEdge.loopPolicy().loopType());
    assertEquals(3, originalBackEdge.loopPolicy().maxIterations());
  }

  @Test
  void validSingleSplitJoinPairIsAccepted() {
    ValidationResult result =
        WorkflowDefinitionValidator.validate(WorkflowDefinitionFixtures.parallelWorkflow());
    assertTrue(result.isValid(), result.findings().toString());
  }

  @Test
  void validNestedSplitJoinPairsAreAccepted() {
    StartActivity start =
        new StartActivity("start", "Start", "", null, null, null, null, null, Map.of());
    ParallelSplitActivity outerSplit =
        new ParallelSplitActivity(
            "outerSplit", "Outer Split", "", null, null, null, null, null, Map.of(), "outer");
    ParallelSplitActivity innerSplit =
        new ParallelSplitActivity(
            "innerSplit", "Inner Split", "", null, null, null, null, null, Map.of(), "inner");
    HumanTaskActivity taskA =
        new HumanTaskActivity(
            "taskA",
            "Task A",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "taskType",
            "roleA",
            List.of("DONE"),
            List.of(),
            null,
            null);
    HumanTaskActivity taskB =
        new HumanTaskActivity(
            "taskB",
            "Task B",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "taskType",
            "roleB",
            List.of("DONE"),
            List.of(),
            null,
            null);
    HumanTaskActivity taskC =
        new HumanTaskActivity(
            "taskC",
            "Task C",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "taskType",
            "roleC",
            List.of("DONE"),
            List.of(),
            null,
            null);
    ParallelJoinActivity innerJoin =
        new ParallelJoinActivity(
            "innerJoin", "Inner Join", "", null, null, null, null, null, Map.of(), "inner");
    ParallelJoinActivity outerJoin =
        new ParallelJoinActivity(
            "outerJoin", "Outer Join", "", null, null, null, null, null, Map.of(), "outer");
    EndActivity end = new EndActivity("end", "End", "", null, null, null, null, null, Map.of());

    WorkflowDefinition definition =
        new WorkflowDefinition(
            "nested.parallel",
            "Nested Parallel",
            "",
            new SemanticVersion("1.0.0"),
            WorkflowDefinitionStatus.DRAFT,
            List.of(start, outerSplit, innerSplit, taskA, taskB, taskC, innerJoin, outerJoin, end),
            List.of(
                new Transition("start", "outerSplit", null, null, 0, ""),
                new Transition("outerSplit", "innerSplit", null, null, 0, ""),
                new Transition("outerSplit", "taskB", null, null, 1, ""),
                new Transition("innerSplit", "taskA", null, null, 0, ""),
                new Transition("innerSplit", "taskC", null, null, 1, ""),
                new Transition("taskA", "innerJoin", null, null, 0, ""),
                new Transition("taskC", "innerJoin", null, null, 0, ""),
                new Transition("innerJoin", "outerJoin", null, null, 0, ""),
                new Transition("taskB", "outerJoin", null, null, 0, ""),
                new Transition("outerJoin", "end", null, null, 0, "")),
            "start",
            List.of("end"),
            Map.of(),
            Instant.now(),
            "tester",
            "correlationKey",
            "commandId");

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertTrue(result.isValid(), result.findings().toString());
  }

  @Test
  void missingPairKeyIsRejected() {
    StartActivity start =
        new StartActivity("start", "Start", "", null, null, null, null, null, Map.of());
    ParallelSplitActivity split =
        new ParallelSplitActivity("split", "Split", "", null, null, null, null, null, Map.of(), "");
    ParallelJoinActivity join =
        new ParallelJoinActivity(
            "join", "Join", "", null, null, null, null, null, Map.of(), "pair-1");
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
            "missing.pair",
            "Missing Pair",
            "",
            new SemanticVersion("1.0.0"),
            WorkflowDefinitionStatus.DRAFT,
            List.of(start, split, task, join, end),
            List.of(
                new Transition("start", "split", null, null, 0, ""),
                new Transition("split", "task", null, null, 0, ""),
                new Transition("task", "join", null, null, 0, ""),
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
    assertHasCode(result, "WF_SPLIT_MISSING_PAIR_KEY");
  }

  @Test
  void unmatchedSplitIsRejected() {
    StartActivity start =
        new StartActivity("start", "Start", "", null, null, null, null, null, Map.of());
    ParallelSplitActivity split =
        new ParallelSplitActivity(
            "split", "Split", "", null, null, null, null, null, Map.of(), "pair-1");
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
            "unmatched.split",
            "Unmatched Split",
            "",
            new SemanticVersion("1.0.0"),
            WorkflowDefinitionStatus.DRAFT,
            List.of(start, split, task, end),
            List.of(
                new Transition("start", "split", null, null, 0, ""),
                new Transition("split", "task", null, null, 0, ""),
                new Transition("split", "end", null, null, 1, ""),
                new Transition("task", "end", null, null, 0, "")),
            "start",
            List.of("end"),
            Map.of(),
            Instant.now(),
            "tester",
            "correlationKey",
            "commandId");

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_UNMATCHED_SPLIT");
  }

  @Test
  void unmatchedJoinIsRejected() {
    StartActivity start =
        new StartActivity("start", "Start", "", null, null, null, null, null, Map.of());
    ParallelJoinActivity join =
        new ParallelJoinActivity(
            "join", "Join", "", null, null, null, null, null, Map.of(), "pair-1");
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
            "unmatched.join",
            "Unmatched Join",
            "",
            new SemanticVersion("1.0.0"),
            WorkflowDefinitionStatus.DRAFT,
            List.of(start, task, join, end),
            List.of(
                new Transition("start", "task", null, null, 0, ""),
                new Transition("task", "join", null, null, 0, ""),
                new Transition("task", "end", null, null, 1, ""),
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
    assertHasCode(result, "WF_UNMATCHED_JOIN");
  }

  @Test
  void duplicatePairKeysAreRejected() {
    StartActivity start =
        new StartActivity("start", "Start", "", null, null, null, null, null, Map.of());
    ParallelSplitActivity split1 =
        new ParallelSplitActivity(
            "split1", "Split 1", "", null, null, null, null, null, Map.of(), "pair-1");
    ParallelSplitActivity split2 =
        new ParallelSplitActivity(
            "split2", "Split 2", "", null, null, null, null, null, Map.of(), "pair-1");
    ParallelJoinActivity join =
        new ParallelJoinActivity(
            "join", "Join", "", null, null, null, null, null, Map.of(), "pair-1");
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
            "duplicate.pair",
            "Duplicate Pair",
            "",
            new SemanticVersion("1.0.0"),
            WorkflowDefinitionStatus.DRAFT,
            List.of(start, split1, split2, task, join, end),
            List.of(
                new Transition("start", "split1", null, null, 0, ""),
                new Transition("split1", "task", null, null, 0, ""),
                new Transition("split2", "task", null, null, 0, ""),
                new Transition("task", "join", null, null, 0, ""),
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
    assertHasCode(result, "WF_DUPLICATE_SPLIT_PAIR_KEY");
  }

  @Test
  void crossingGatewayPairsAreRejected() {
    StartActivity start =
        new StartActivity("start", "Start", "", null, null, null, null, null, Map.of());
    ParallelSplitActivity splitA =
        new ParallelSplitActivity(
            "splitA", "Split A", "", null, null, null, null, null, Map.of(), "pair-a");
    ParallelSplitActivity splitB =
        new ParallelSplitActivity(
            "splitB", "Split B", "", null, null, null, null, null, Map.of(), "pair-b");
    ParallelJoinActivity joinA =
        new ParallelJoinActivity(
            "joinA", "Join A", "", null, null, null, null, null, Map.of(), "pair-a");
    ParallelJoinActivity joinB =
        new ParallelJoinActivity(
            "joinB", "Join B", "", null, null, null, null, null, Map.of(), "pair-b");
    HumanTaskActivity taskA =
        new HumanTaskActivity(
            "taskA",
            "Task A",
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
    HumanTaskActivity taskB =
        new HumanTaskActivity(
            "taskB",
            "Task B",
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
    HumanTaskActivity taskC =
        new HumanTaskActivity(
            "taskC",
            "Task C",
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
            "crossing.pair",
            "Crossing Pair",
            "",
            new SemanticVersion("1.0.0"),
            WorkflowDefinitionStatus.DRAFT,
            List.of(start, splitA, splitB, taskA, taskB, taskC, joinA, joinB, end),
            List.of(
                new Transition("start", "splitA", null, null, 0, ""),
                new Transition("splitA", "splitB", null, null, 0, ""),
                new Transition("splitA", "taskA", null, null, 1, ""),
                new Transition("splitB", "taskB", null, null, 0, ""),
                new Transition("splitB", "joinA", null, null, 1, ""),
                new Transition("taskA", "joinA", null, null, 0, ""),
                new Transition("taskB", "joinB", null, null, 0, ""),
                new Transition("taskC", "joinB", null, null, 0, ""),
                new Transition("joinA", "end", null, null, 0, ""),
                new Transition("joinB", "end", null, null, 0, "")),
            "start",
            List.of("end"),
            Map.of(),
            Instant.now(),
            "tester",
            "correlationKey",
            "commandId");

    ValidationResult result = WorkflowDefinitionValidator.validate(definition);
    assertFalse(result.isValid());
    assertHasCode(result, "WF_JOIN_COMBINES_UNRELATED_BRANCHES");
  }

  @Test
  void branchUnableToReachPairedJoinIsRejected() {
    StartActivity start =
        new StartActivity("start", "Start", "", null, null, null, null, null, Map.of());
    ParallelSplitActivity split =
        new ParallelSplitActivity(
            "split", "Split", "", null, null, null, null, null, Map.of(), "pair-1");
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
    ParallelJoinActivity join =
        new ParallelJoinActivity(
            "join", "Join", "", null, null, null, null, null, Map.of(), "pair-1");
    EndActivity end = new EndActivity("end", "End", "", null, null, null, null, null, Map.of());

    WorkflowDefinition definition =
        new WorkflowDefinition(
            "branch.misses.join",
            "Branch Misses Join",
            "",
            new SemanticVersion("1.0.0"),
            WorkflowDefinitionStatus.DRAFT,
            List.of(start, split, task, join, end),
            List.of(
                new Transition("start", "split", null, null, 0, ""),
                new Transition("split", "task", null, null, 0, ""),
                new Transition("split", "end", null, null, 1, ""),
                new Transition("task", "join", null, null, 0, ""),
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
    assertHasCode(result, "WF_BRANCH_CANNOT_REACH_JOIN");
  }

  @Test
  void gatewayPairKeyJsonRoundTrip() {
    WorkflowDefinition original = WorkflowDefinitionFixtures.parallelWorkflow();
    String json = WorkflowDefinitionJson.serialize(original);
    WorkflowDefinition roundTripped = WorkflowDefinitionJson.deserialize(json);

    ParallelSplitActivity split =
        (ParallelSplitActivity)
            roundTripped.activities().stream()
                .filter(a -> a.type() == ActivityType.PARALLEL_SPLIT)
                .findFirst()
                .orElseThrow();
    ParallelJoinActivity join =
        (ParallelJoinActivity)
            roundTripped.activities().stream()
                .filter(a -> a.type() == ActivityType.PARALLEL_JOIN)
                .findFirst()
                .orElseThrow();
    assertEquals("pair-1", split.pairKey());
    assertEquals("pair-1", join.pairKey());
    assertTrue(WorkflowDefinitionValidator.validate(roundTripped).isValid());
  }

  private static WorkflowDefinition loopWorkflow() {
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
    return new WorkflowDefinition(
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
            new Transition(
                "decision",
                "task",
                "RETRY",
                null,
                0,
                "",
                new LoopPolicy("retry-loop", LoopType.BOUNDED, 3, null, null, null)),
            new Transition("decision", "end", "PROCEED", null, 0, "")),
        "start",
        List.of("end"),
        Map.of(),
        Instant.now(),
        "tester",
        "correlationKey",
        "commandId");
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
