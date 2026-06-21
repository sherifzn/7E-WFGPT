package com.sevenewf.workflow.domain.workflowdefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

final class WorkflowDefinitionFixtures {

  static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

  private WorkflowDefinitionFixtures() {}

  static WorkflowDefinition simpleLinearWorkflow() {
    StartActivity start =
        new StartActivity("start", "Start", "", null, null, null, null, null, Map.of());
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
        "simple.linear",
        "Simple Linear Workflow",
        "A simple linear workflow",
        new SemanticVersion("1.0.0"),
        WorkflowDefinitionStatus.DRAFT,
        List.of(start, task, end),
        List.of(
            new Transition("start", "task", null, null, 0, ""),
            new Transition("task", "end", null, null, 0, "")),
        "start",
        List.of("end"),
        Map.of(),
        CREATED_AT,
        "tester",
        "correlationKey",
        "commandId");
  }

  static WorkflowDefinition parallelWorkflow() {
    StartActivity start =
        new StartActivity("start", "Start", "", null, null, null, null, null, Map.of());
    ParallelSplitActivity split =
        new ParallelSplitActivity(
            "split", "Split", "", null, null, null, null, null, Map.of(), "pair-1");
    HumanTaskActivity branchA =
        new HumanTaskActivity(
            "branchA",
            "Branch A",
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
    HumanTaskActivity branchB =
        new HumanTaskActivity(
            "branchB",
            "Branch B",
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
    ParallelJoinActivity join =
        new ParallelJoinActivity(
            "join", "Join", "", null, null, null, null, null, Map.of(), "pair-1");
    EndActivity end = new EndActivity("end", "End", "", null, null, null, null, null, Map.of());

    return new WorkflowDefinition(
        "parallel.workflow",
        "Parallel Workflow",
        "",
        new SemanticVersion("1.0.0"),
        WorkflowDefinitionStatus.DRAFT,
        List.of(start, split, branchA, branchB, join, end),
        List.of(
            new Transition("start", "split", null, null, 0, ""),
            new Transition("split", "branchA", null, null, 0, ""),
            new Transition("split", "branchB", null, null, 1, ""),
            new Transition("branchA", "join", null, null, 0, ""),
            new Transition("branchB", "join", null, null, 0, ""),
            new Transition("join", "end", null, null, 0, "")),
        "start",
        List.of("end"),
        Map.of(),
        CREATED_AT,
        "tester",
        "correlationKey",
        "commandId");
  }

  static WorkflowDefinition childWorkflow() {
    StartActivity start =
        new StartActivity("start", "Start", "", null, null, null, null, null, Map.of());
    ChildWorkflowActivity child =
        new ChildWorkflowActivity(
            "child",
            "Child Workflow",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "inspection",
            ">=1.0.0",
            "parentReference",
            "propertyReference",
            DuplicatePolicy.CORRELATE_ACTIVE,
            "wait-for-completion");
    EndActivity end = new EndActivity("end", "End", "", null, null, null, null, null, Map.of());

    return new WorkflowDefinition(
        "child.workflow",
        "Child Workflow",
        "",
        new SemanticVersion("1.0.0"),
        WorkflowDefinitionStatus.DRAFT,
        List.of(start, child, end),
        List.of(
            new Transition("start", "child", null, null, 0, ""),
            new Transition("child", "end", null, null, 0, "")),
        "start",
        List.of("end"),
        Map.of(),
        CREATED_AT,
        "tester",
        "correlationKey",
        "commandId");
  }

  static WorkflowDefinition waitEventWorkflow() {
    StartActivity start =
        new StartActivity("start", "Start", "", null, null, null, null, null, Map.of());
    WaitEventActivity wait =
        new WaitEventActivity(
            "wait",
            "Wait Event",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "InspectionCompleted",
            "inspectionId",
            "timeout-policy-1",
            "timeout");
    EndActivity end = new EndActivity("end", "End", "", null, null, null, null, null, Map.of());

    return new WorkflowDefinition(
        "wait.event",
        "Wait Event Workflow",
        "",
        new SemanticVersion("1.0.0"),
        WorkflowDefinitionStatus.DRAFT,
        List.of(start, wait, end),
        List.of(
            new Transition("start", "wait", null, null, 0, ""),
            new Transition("wait", "end", null, null, 0, "")),
        "start",
        List.of("end"),
        Map.of(),
        CREATED_AT,
        "tester",
        "correlationKey",
        "commandId");
  }

  static WorkflowDefinition timerWorkflow() {
    StartActivity start =
        new StartActivity("start", "Start", "", null, null, null, null, null, Map.of());
    TimerActivity timer =
        new TimerActivity(
            "timer", "Timer", "", null, null, null, null, null, Map.of(), "PT24H", null, "end");
    EndActivity end = new EndActivity("end", "End", "", null, null, null, null, null, Map.of());

    return new WorkflowDefinition(
        "timer.workflow",
        "Timer Workflow",
        "",
        new SemanticVersion("1.0.0"),
        WorkflowDefinitionStatus.DRAFT,
        List.of(start, timer, end),
        List.of(
            new Transition("start", "timer", null, null, 0, ""),
            new Transition("timer", "end", null, null, 0, "")),
        "start",
        List.of("end"),
        Map.of(),
        CREATED_AT,
        "tester",
        "correlationKey",
        "commandId");
  }

  static WorkflowDefinition simplifiedKeyHandover() {
    StartActivity start =
        new StartActivity("start", "Start", "", null, null, null, null, null, Map.of());
    ParallelSplitActivity split =
        new ParallelSplitActivity(
            "split", "Parallel Split", "", null, null, null, null, null, Map.of(), "pair-1");
    HumanTaskActivity finance =
        new HumanTaskActivity(
            "finance",
            "Finance",
            "Finance clearance",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "finance.clearance",
            "finance-officer",
            List.of("APPROVED", "REJECTED"),
            List.of("signature"),
            null,
            null);
    HumanTaskActivity legal =
        new HumanTaskActivity(
            "legal",
            "Legal",
            "Legal clearance",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "legal.clearance",
            "legal-officer",
            List.of("APPROVED", "REJECTED"),
            List.of("signature"),
            null,
            null);
    ChildWorkflowActivity inspection =
        new ChildWorkflowActivity(
            "inspection",
            "Inspection",
            "Inspection child workflow",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "inspection",
            ">=1.0.0",
            "propertyReference",
            "propertyReference",
            DuplicatePolicy.CORRELATE_ACTIVE,
            "wait-for-completion");
    HumanTaskActivity handover =
        new HumanTaskActivity(
            "handover",
            "Handover",
            "Final handover",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "handover.task",
            "handover-officer",
            List.of("COMPLETED"),
            List.of("signature"),
            null,
            null);
    ParallelJoinActivity join =
        new ParallelJoinActivity(
            "join", "Parallel Join", "", null, null, null, null, null, Map.of(), "pair-1");
    DecisionActivity decision =
        new DecisionActivity(
            "decision",
            "Final Decision",
            "",
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            "final.decision",
            "rule-set-1",
            List.of("GREEN", "AMBER", "RED"));
    EndActivity end = new EndActivity("end", "End", "", null, null, null, null, null, Map.of());

    return new WorkflowDefinition(
        "key.handover",
        "Key Handover",
        "Simplified generic Key Handover definition",
        new SemanticVersion("1.0.0"),
        WorkflowDefinitionStatus.DRAFT,
        List.of(start, split, finance, legal, inspection, handover, join, decision, end),
        List.of(
            new Transition("start", "split", null, null, 0, ""),
            new Transition("split", "finance", null, null, 0, ""),
            new Transition("split", "legal", null, null, 1, ""),
            new Transition("split", "inspection", null, null, 2, ""),
            new Transition("inspection", "handover", null, null, 0, ""),
            new Transition("finance", "join", null, null, 0, ""),
            new Transition("legal", "join", null, null, 0, ""),
            new Transition("handover", "join", null, null, 0, ""),
            new Transition("join", "decision", null, null, 0, ""),
            new Transition("decision", "end", "GREEN", null, 0, ""),
            new Transition("decision", "end", "AMBER", null, 1, ""),
            new Transition("decision", "end", "RED", null, 2, "")),
        "start",
        List.of("end"),
        Map.of("domain", "key-handover"),
        CREATED_AT,
        "tester",
        "propertyReference",
        "commandId");
  }

  static WorkflowDefinition publishedDefinition() {
    return new WorkflowDefinition(
        "published.workflow",
        "Published Workflow",
        "",
        new SemanticVersion("1.0.0"),
        WorkflowDefinitionStatus.PUBLISHED,
        List.of(
            new StartActivity("start", "Start", "", null, null, null, null, null, Map.of()),
            new EndActivity("end", "End", "", null, null, null, null, null, Map.of())),
        List.of(new Transition("start", "end", null, null, 0, "")),
        "start",
        List.of("end"),
        Map.of(),
        CREATED_AT,
        "tester",
        "correlationKey",
        "commandId");
  }
}
