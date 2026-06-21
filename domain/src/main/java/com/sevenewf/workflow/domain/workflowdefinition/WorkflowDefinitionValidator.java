package com.sevenewf.workflow.domain.workflowdefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class WorkflowDefinitionValidator {

  private WorkflowDefinitionValidator() {}

  public static ValidationResult validate(WorkflowDefinition definition) {
    List<ValidationFinding> findings = new ArrayList<>();
    if (definition == null) {
      findings.add(
          ValidationFinding.of(
              ValidationSeverity.CRITICAL,
              "WF_NULL",
              "Workflow definition is required",
              "unknown"));
      return new ValidationResult(findings);
    }

    String workflowKey = definition.workflowKey();
    Map<String, ActivityDefinition> activitiesByKey = new HashMap<>();
    Set<String> duplicateKeys = new HashSet<>();
    ActivityDefinition startActivity = null;
    int startCount = 0;
    int endCount = 0;

    for (ActivityDefinition activity : definition.activities()) {
      if (activitiesByKey.containsKey(activity.activityKey())) {
        duplicateKeys.add(activity.activityKey());
      } else {
        activitiesByKey.put(activity.activityKey(), activity);
      }
      if (activity.type() == ActivityType.START) {
        startActivity = activity;
        startCount++;
      }
      if (activity.type() == ActivityType.END) {
        endCount++;
      }
    }

    if (workflowKey == null || workflowKey.isBlank()) {
      findings.add(
          ValidationFinding.of(
              ValidationSeverity.ERROR,
              "WF_MISSING_KEY",
              "Workflow key is required",
              workflowKey == null ? "" : workflowKey));
    }

    if (definition.version() == null) {
      findings.add(
          ValidationFinding.of(
              ValidationSeverity.ERROR,
              "WF_MISSING_VERSION",
              "Workflow version is required",
              workflowKey));
    }

    if (startCount == 0) {
      findings.add(
          ValidationFinding.of(
              ValidationSeverity.ERROR,
              "WF_NO_START",
              "Workflow must contain exactly one START activity",
              workflowKey));
    }
    if (startCount > 1) {
      findings.add(
          ValidationFinding.of(
              ValidationSeverity.ERROR,
              "WF_MULTIPLE_STARTS",
              "Workflow must contain exactly one START activity, found " + startCount,
              workflowKey));
    }

    if (endCount == 0) {
      findings.add(
          ValidationFinding.of(
              ValidationSeverity.ERROR,
              "WF_NO_END",
              "Workflow must contain at least one END activity",
              workflowKey));
    }

    for (String key : duplicateKeys) {
      findings.add(
          ValidationFinding.of(
              ValidationSeverity.ERROR,
              "WF_DUPLICATE_ACTIVITY_KEY",
              "Duplicate activity key: " + key,
              workflowKey,
              key));
    }

    if (startActivity != null
        && !Objects.equals(definition.startActivityKey(), startActivity.activityKey())) {
      findings.add(
          ValidationFinding.of(
              ValidationSeverity.ERROR,
              "WF_START_KEY_MISMATCH",
              "startActivityKey does not reference the START activity",
              workflowKey));
    }

    if (startActivity != null
        && !definition.terminalActivityKeys().contains(startActivity.activityKey())) {
      // terminal check for start is handled separately
    }

    Map<String, List<Transition>> outgoingBySource = new HashMap<>();
    Map<String, List<Transition>> incomingByTarget = new HashMap<>();

    for (Transition transition : definition.transitions()) {
      if (!activitiesByKey.containsKey(transition.sourceActivityKey())) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_UNKNOWN_TRANSITION_SOURCE",
                "Transition references unknown source activity: " + transition.sourceActivityKey(),
                workflowKey,
                transition));
      }
      if (!activitiesByKey.containsKey(transition.targetActivityKey())) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_UNKNOWN_TRANSITION_TARGET",
                "Transition references unknown target activity: " + transition.targetActivityKey(),
                workflowKey,
                transition));
      }
      if (Objects.equals(transition.sourceActivityKey(), transition.targetActivityKey())) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_SELF_LOOP",
                "Transition forms a self-loop without explicit loop policy",
                workflowKey,
                transition));
      }
      outgoingBySource
          .computeIfAbsent(transition.sourceActivityKey(), k -> new ArrayList<>())
          .add(transition);
      incomingByTarget
          .computeIfAbsent(transition.targetActivityKey(), k -> new ArrayList<>())
          .add(transition);
    }

    Set<String> reachableKeys = computeReachable(definition, activitiesByKey);
    for (ActivityDefinition activity : definition.activities()) {
      if (!reachableKeys.contains(activity.activityKey())) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_UNREACHABLE_ACTIVITY",
                "Activity is not reachable from the START activity",
                workflowKey,
                activity.activityKey()));
      }
    }

    for (ActivityDefinition activity : definition.activities()) {
      String key = activity.activityKey();
      List<Transition> outgoing = outgoingBySource.getOrDefault(key, List.of());
      List<Transition> incoming = incomingByTarget.getOrDefault(key, List.of());
      boolean isTerminal = definition.terminalActivityKeys().contains(key);

      if (isTerminal && !outgoing.isEmpty()) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_TERMINAL_HAS_OUTGOING",
                "Terminal activity must not have outgoing transitions",
                workflowKey,
                key));
      }

      if (activity.type() == ActivityType.END && !outgoing.isEmpty()) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_END_HAS_OUTGOING",
                "END activity must not have outgoing transitions",
                workflowKey,
                key));
      }

      if (activity.type() != ActivityType.END && !isTerminal && outgoing.isEmpty()) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_MISSING_OUTGOING_TRANSITION",
                "Non-terminal activity must have at least one outgoing transition",
                workflowKey,
                key));
      }

      if (activity.type() == ActivityType.PARALLEL_SPLIT && outgoing.size() < 2) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_PARALLEL_SPLIT_BRANCHES",
                "PARALLEL_SPLIT must have at least two outgoing branches",
                workflowKey,
                key));
      }

      if (activity.type() == ActivityType.PARALLEL_JOIN && incoming.size() < 2) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_PARALLEL_JOIN_BRANCHES",
                "PARALLEL_JOIN must have at least two incoming branches",
                workflowKey,
                key));
      }

      if (activity.type() == ActivityType.DECISION) {
        DecisionActivity decision = (DecisionActivity) activity;
        if (decision.namedOutcomes() == null || decision.namedOutcomes().isEmpty()) {
          findings.add(
              ValidationFinding.of(
                  ValidationSeverity.ERROR,
                  "WF_DECISION_NO_OUTCOMES",
                  "DECISION activity must define named outcomes",
                  workflowKey,
                  key));
        } else {
          Set<String> seenOutcomes = new HashSet<>();
          for (String outcome : decision.namedOutcomes()) {
            if (!seenOutcomes.add(outcome)) {
              findings.add(
                  ValidationFinding.of(
                      ValidationSeverity.ERROR,
                      "WF_DECISION_DUPLICATE_OUTCOME",
                      "Duplicate decision outcome: " + outcome,
                      workflowKey,
                      key));
            }
          }
        }
      }

      if (activity.type() == ActivityType.HUMAN_TASK) {
        HumanTaskActivity humanTask = (HumanTaskActivity) activity;
        if (humanTask.eligibleRoleKey() == null || humanTask.eligibleRoleKey().isBlank()) {
          findings.add(
              ValidationFinding.of(
                  ValidationSeverity.ERROR,
                  "WF_HUMAN_TASK_NO_ROLE",
                  "HUMAN_TASK must define an eligible role",
                  workflowKey,
                  key));
        }
        if (humanTask.allowedOutcomes() == null || humanTask.allowedOutcomes().isEmpty()) {
          findings.add(
              ValidationFinding.of(
                  ValidationSeverity.ERROR,
                  "WF_HUMAN_TASK_NO_OUTCOMES",
                  "HUMAN_TASK must define allowed outcomes",
                  workflowKey,
                  key));
        }
      }

      if (activity.type() == ActivityType.CHILD_WORKFLOW) {
        ChildWorkflowActivity child = (ChildWorkflowActivity) activity;
        if (child.correlationKeyMapping() == null || child.correlationKeyMapping().isBlank()) {
          findings.add(
              ValidationFinding.of(
                  ValidationSeverity.ERROR,
                  "WF_CHILD_NO_CORRELATION",
                  "CHILD_WORKFLOW must define a correlation-key mapping",
                  workflowKey,
                  key));
        }
        if (child.businessKeyMapping() == null || child.businessKeyMapping().isBlank()) {
          findings.add(
              ValidationFinding.of(
                  ValidationSeverity.ERROR,
                  "WF_CHILD_NO_BUSINESS_KEY",
                  "CHILD_WORKFLOW must define a business-key mapping",
                  workflowKey,
                  key));
        }
      }

      if (activity.type() == ActivityType.WAIT_EVENT) {
        WaitEventActivity wait = (WaitEventActivity) activity;
        if (wait.eventType() == null || wait.eventType().isBlank()) {
          findings.add(
              ValidationFinding.of(
                  ValidationSeverity.ERROR,
                  "WF_WAIT_NO_EVENT_TYPE",
                  "WAIT_EVENT must define an event type",
                  workflowKey,
                  key));
        }
      }

      if (activity.type() == ActivityType.TIMER) {
        TimerActivity timer = (TimerActivity) activity;
        boolean hasDuration = timer.duration() != null && !timer.duration().isBlank();
        boolean hasPolicy =
            timer.durationPolicyRef() != null && !timer.durationPolicyRef().isBlank();
        if (!hasDuration && !hasPolicy) {
          findings.add(
              ValidationFinding.of(
                  ValidationSeverity.ERROR,
                  "WF_TIMER_NO_DURATION",
                  "TIMER must define a duration or a duration policy reference",
                  workflowKey,
                  key));
        }
      }
    }

    detectCyclesAndLoops(definition, activitiesByKey, outgoingBySource, workflowKey, findings);
    detectConflictingPriorities(outgoingBySource, workflowKey, findings);
    detectOrphanJoins(definition, activitiesByKey, outgoingBySource, incomingByTarget, findings);
    detectUnsupportedCombinations(
        definition, activitiesByKey, outgoingBySource, incomingByTarget, findings);

    return new ValidationResult(findings);
  }

  private static Set<String> computeReachable(
      WorkflowDefinition definition, Map<String, ActivityDefinition> activitiesByKey) {
    Set<String> reachable = new HashSet<>();
    if (!activitiesByKey.containsKey(definition.startActivityKey())) {
      return reachable;
    }
    Deque<String> queue = new ArrayDeque<>();
    queue.add(definition.startActivityKey());
    while (!queue.isEmpty()) {
      String current = queue.poll();
      if (!reachable.add(current)) {
        continue;
      }
      for (Transition transition : definition.transitions()) {
        if (Objects.equals(transition.sourceActivityKey(), current)) {
          queue.add(transition.targetActivityKey());
        }
      }
    }
    return reachable;
  }

  private static void detectCyclesAndLoops(
      WorkflowDefinition definition,
      Map<String, ActivityDefinition> activitiesByKey,
      Map<String, List<Transition>> outgoingBySource,
      String workflowKey,
      List<ValidationFinding> findings) {
    if (activitiesByKey.isEmpty()) {
      return;
    }
    boolean loopPolicyDeclared = definition.metadata().containsKey("loopPolicy");
    if (loopPolicyDeclared) {
      return;
    }
    Set<String> visiting = new HashSet<>();
    Set<String> visited = new HashSet<>();
    for (ActivityDefinition activity : definition.activities()) {
      if (!visited.contains(activity.activityKey())) {
        if (hasCycleFrom(
            activity.activityKey(), visiting, visited, outgoingBySource, new HashSet<>())) {
          findings.add(
              ValidationFinding.of(
                  ValidationSeverity.ERROR,
                  "WF_UNDECLARED_CYCLE",
                  "Workflow contains a cycle without an explicitly declared loop policy",
                  workflowKey));
          return;
        }
      }
    }
  }

  private static boolean hasCycleFrom(
      String current,
      Set<String> visiting,
      Set<String> visited,
      Map<String, List<Transition>> outgoingBySource,
      Set<String> path) {
    if (visiting.contains(current)) {
      return true;
    }
    if (visited.contains(current)) {
      return false;
    }
    visiting.add(current);
    path.add(current);
    for (Transition transition : outgoingBySource.getOrDefault(current, List.of())) {
      if (hasCycleFrom(transition.targetActivityKey(), visiting, visited, outgoingBySource, path)) {
        return true;
      }
    }
    visiting.remove(current);
    visited.add(current);
    path.remove(current);
    return false;
  }

  private static void detectConflictingPriorities(
      Map<String, List<Transition>> outgoingBySource,
      String workflowKey,
      List<ValidationFinding> findings) {
    for (Map.Entry<String, List<Transition>> entry : outgoingBySource.entrySet()) {
      Map<String, List<Transition>> byOutcomeAndPriority = new HashMap<>();
      for (Transition transition : entry.getValue()) {
        String outcome = transition.outcome() == null ? "" : transition.outcome();
        String key = outcome + "|" + transition.priority();
        byOutcomeAndPriority.computeIfAbsent(key, k -> new ArrayList<>()).add(transition);
      }
      for (Map.Entry<String, List<Transition>> conflict : byOutcomeAndPriority.entrySet()) {
        if (conflict.getValue().size() > 1) {
          findings.add(
              ValidationFinding.of(
                  ValidationSeverity.ERROR,
                  "WF_CONFLICTING_TRANSITION_PRIORITY",
                  "Transitions from source have conflicting outcome/priority combination",
                  workflowKey,
                  conflict.getValue().get(0)));
        }
      }
    }
  }

  private static void detectOrphanJoins(
      WorkflowDefinition definition,
      Map<String, ActivityDefinition> activitiesByKey,
      Map<String, List<Transition>> outgoingBySource,
      Map<String, List<Transition>> incomingByTarget,
      List<ValidationFinding> findings) {
    List<String> splitKeys =
        definition.activities().stream()
            .filter(a -> a.type() == ActivityType.PARALLEL_SPLIT)
            .map(ActivityDefinition::activityKey)
            .toList();

    for (ActivityDefinition activity : definition.activities()) {
      if (activity.type() != ActivityType.PARALLEL_JOIN) {
        continue;
      }
      List<Transition> incoming = incomingByTarget.getOrDefault(activity.activityKey(), List.of());
      if (incoming.size() < 2) {
        continue;
      }

      boolean allHaveSplitAncestry = true;
      for (Transition incomingTransition : incoming) {
        String source = incomingTransition.sourceActivityKey();
        if (!isReachableFromAny(source, splitKeys, activitiesByKey, outgoingBySource)) {
          allHaveSplitAncestry = false;
          break;
        }
      }

      if (!allHaveSplitAncestry) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_ORPHAN_JOIN",
                "PARALLEL_JOIN has no corresponding PARALLEL_SPLIT ancestry",
                definition.workflowKey(),
                activity.activityKey()));
      }
    }
  }

  private static boolean isReachableFromAny(
      String target,
      List<String> sources,
      Map<String, ActivityDefinition> activitiesByKey,
      Map<String, List<Transition>> outgoingBySource) {
    if (sources.isEmpty()) {
      return false;
    }
    Set<String> reachable = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>(sources);
    while (!queue.isEmpty()) {
      String current = queue.poll();
      if (Objects.equals(current, target)) {
        return true;
      }
      if (!reachable.add(current)) {
        continue;
      }
      for (Transition transition : outgoingBySource.getOrDefault(current, List.of())) {
        queue.add(transition.targetActivityKey());
      }
    }
    return false;
  }

  private static void detectUnsupportedCombinations(
      WorkflowDefinition definition,
      Map<String, ActivityDefinition> activitiesByKey,
      Map<String, List<Transition>> outgoingBySource,
      Map<String, List<Transition>> incomingByTarget,
      List<ValidationFinding> findings) {
    String workflowKey = definition.workflowKey();
    for (Transition transition : definition.transitions()) {
      ActivityDefinition source = activitiesByKey.get(transition.sourceActivityKey());
      ActivityDefinition target = activitiesByKey.get(transition.targetActivityKey());
      if (source == null || target == null) {
        continue;
      }
      if (source.type() == ActivityType.START && transition.outcome() != null) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_UNSUPPORTED_START_OUTCOME",
                "START activity transition must not specify an outcome",
                workflowKey,
                transition));
      }
      if (source.type() == ActivityType.PARALLEL_SPLIT
          && transition.outcome() != null
          && !transition.outcome().isBlank()) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_UNSUPPORTED_SPLIT_OUTCOME",
                "PARALLEL_SPLIT transition must not specify an outcome",
                workflowKey,
                transition));
      }
      if (source.type() == ActivityType.PARALLEL_JOIN
          && transition.outcome() != null
          && !transition.outcome().isBlank()) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_UNSUPPORTED_JOIN_OUTCOME",
                "PARALLEL_JOIN transition must not specify an outcome",
                workflowKey,
                transition));
      }
      if (source.type() == ActivityType.END) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_UNSUPPORTED_END_SOURCE",
                "END activity cannot be the source of a transition",
                workflowKey,
                transition));
      }
      if (target.type() == ActivityType.START) {
        findings.add(
            ValidationFinding.of(
                ValidationSeverity.ERROR,
                "WF_UNSUPPORTED_START_TARGET",
                "START activity cannot be the target of a transition",
                workflowKey,
                transition));
      }
    }

    for (ActivityDefinition activity : definition.activities()) {
      if (activity.type() == ActivityType.CHILD_WORKFLOW) {
        List<Transition> outgoing =
            outgoingBySource.getOrDefault(activity.activityKey(), List.of());
        if (outgoing.size() > 1) {
          findings.add(
              ValidationFinding.of(
                  ValidationSeverity.ERROR,
                  "WF_UNSUPPORTED_CHILD_BRANCHES",
                  "CHILD_WORKFLOW supports only a single outgoing transition",
                  workflowKey,
                  activity.activityKey()));
        }
      }
      if (activity.type() == ActivityType.PARALLEL_SPLIT) {
        List<Transition> outgoing =
            outgoingBySource.getOrDefault(activity.activityKey(), List.of());
        boolean directJoin =
            outgoing.stream()
                .map(t -> activitiesByKey.get(t.targetActivityKey()))
                .filter(Objects::nonNull)
                .anyMatch(a -> a.type() == ActivityType.PARALLEL_JOIN);
        if (directJoin) {
          findings.add(
              ValidationFinding.of(
                  ValidationSeverity.ERROR,
                  "WF_UNSUPPORTED_EMPTY_PARALLEL",
                  "PARALLEL_SPLIT cannot transition directly to PARALLEL_JOIN",
                  workflowKey,
                  activity.activityKey()));
        }
      }
    }
  }
}
