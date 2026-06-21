package com.sevenewf.workflow.domain.workflowdefinition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class WorkflowDefinitionJson {

  private WorkflowDefinitionJson() {}

  public static String serialize(WorkflowDefinition definition) {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n");
    sb.append("  \"workflowKey\": ").append(Json.escape(definition.workflowKey())).append(",\n");
    sb.append("  \"displayName\": ").append(Json.escape(definition.displayName())).append(",\n");
    sb.append("  \"description\": ").append(Json.escape(definition.description())).append(",\n");
    sb.append("  \"version\": ").append(Json.escape(definition.version().value())).append(",\n");
    sb.append("  \"status\": ").append(Json.escape(definition.status().name())).append(",\n");
    sb.append("  \"activities\": ");
    serializeActivities(definition.activities(), sb, 1);
    sb.append(",\n");
    sb.append("  \"transitions\": ");
    serializeTransitions(definition.transitions(), sb, 1);
    sb.append(",\n");
    sb.append("  \"startActivityKey\": ")
        .append(Json.escape(definition.startActivityKey()))
        .append(",\n");
    sb.append("  \"terminalActivityKeys\": ");
    serializeStrings(definition.terminalActivityKeys(), sb, 1);
    sb.append(",\n");
    sb.append("  \"metadata\": ");
    serializeMetadata(definition.metadata(), sb, 1);
    sb.append(",\n");
    sb.append("  \"createdAt\": ")
        .append(Json.escape(definition.createdAt().toString()))
        .append(",\n");
    sb.append("  \"createdBy\": ").append(Json.escape(definition.createdBy())).append(",\n");
    sb.append("  \"correlationKeyDefinition\": ")
        .append(Json.escape(definition.correlationKeyDefinition()))
        .append(",\n");
    sb.append("  \"idempotencyKeyDefinition\": ")
        .append(Json.escape(definition.idempotencyKeyDefinition()))
        .append("\n");
    sb.append("}");
    return sb.toString();
  }

  private static void serializeActivities(
      List<ActivityDefinition> activities, StringBuilder sb, int indent) {
    sb.append("[\n");
    for (int i = 0; i < activities.size(); i++) {
      ActivityDefinition activity = activities.get(i);
      appendIndent(sb, indent + 1);
      sb.append("{\n");
      appendIndent(sb, indent + 2);
      sb.append("\"type\": ").append(Json.escape(activity.type().name())).append(",\n");
      appendIndent(sb, indent + 2);
      sb.append("\"activityKey\": ").append(Json.escape(activity.activityKey())).append(",\n");
      appendIndent(sb, indent + 2);
      sb.append("\"displayName\": ").append(Json.escape(activity.displayName())).append(",\n");
      appendIndent(sb, indent + 2);
      sb.append("\"description\": ").append(Json.escape(activity.description())).append(",\n");
      appendIndent(sb, indent + 2);
      sb.append("\"inputSchemaRef\": ")
          .append(Json.escape(activity.inputSchemaRef()))
          .append(",\n");
      appendIndent(sb, indent + 2);
      sb.append("\"outputSchemaRef\": ")
          .append(Json.escape(activity.outputSchemaRef()))
          .append(",\n");
      appendIndent(sb, indent + 2);
      sb.append("\"requiredRoleRef\": ")
          .append(Json.escape(activity.requiredRoleRef()))
          .append(",\n");
      appendIndent(sb, indent + 2);
      sb.append("\"retryPolicyRef\": ")
          .append(Json.escape(activity.retryPolicyRef()))
          .append(",\n");
      appendIndent(sb, indent + 2);
      sb.append("\"timeoutRef\": ").append(Json.escape(activity.timeoutRef())).append(",\n");
      appendIndent(sb, indent + 2);
      sb.append("\"metadata\": ");
      serializeMetadata(activity.metadata(), sb, indent + 2);
      serializeActivityPayload(activity, sb, indent + 2);
      sb.append("\n");
      appendIndent(sb, indent + 1);
      sb.append("}");
      if (i < activities.size() - 1) {
        sb.append(",");
      }
      sb.append("\n");
    }
    appendIndent(sb, indent);
    sb.append("]");
  }

  private static void serializeActivityPayload(
      ActivityDefinition activity, StringBuilder sb, int indent) {
    if (activity instanceof HumanTaskActivity h) {
      sb.append(",\n");
      appendIndent(sb, indent);
      sb.append("\"taskTypeKey\": ").append(Json.escape(h.taskTypeKey())).append(",\n");
      appendIndent(sb, indent);
      sb.append("\"eligibleRoleKey\": ").append(Json.escape(h.eligibleRoleKey())).append(",\n");
      appendIndent(sb, indent);
      sb.append("\"allowedOutcomes\": ");
      serializeStrings(h.allowedOutcomes(), sb, indent);
      sb.append(",\n");
      appendIndent(sb, indent);
      sb.append("\"requiredEvidenceTypes\": ");
      serializeStrings(h.requiredEvidenceTypes(), sb, indent);
      sb.append(",\n");
      appendIndent(sb, indent);
      sb.append("\"assignmentPolicyRef\": ")
          .append(Json.escape(h.assignmentPolicyRef()))
          .append(",\n");
      appendIndent(sb, indent);
      sb.append("\"slaPolicyRef\": ").append(Json.escape(h.slaPolicyRef()));
    } else if (activity instanceof ServiceTaskActivity s) {
      sb.append(",\n");
      appendIndent(sb, indent);
      sb.append("\"connectorKey\": ").append(Json.escape(s.connectorKey())).append(",\n");
      appendIndent(sb, indent);
      sb.append("\"operationKey\": ").append(Json.escape(s.operationKey())).append(",\n");
      appendIndent(sb, indent);
      sb.append("\"serviceRetryPolicyRef\": ")
          .append(Json.escape(s.serviceRetryPolicyRef()))
          .append(",\n");
      appendIndent(sb, indent);
      sb.append("\"idempotencyRequired\": ").append(s.idempotencyRequired()).append(",\n");
      appendIndent(sb, indent);
      sb.append("\"inputContractRef\": ").append(Json.escape(s.inputContractRef())).append(",\n");
      appendIndent(sb, indent);
      sb.append("\"outputContractRef\": ").append(Json.escape(s.outputContractRef()));
    } else if (activity instanceof DecisionActivity d) {
      sb.append(",\n");
      appendIndent(sb, indent);
      sb.append("\"decisionKey\": ").append(Json.escape(d.decisionKey())).append(",\n");
      appendIndent(sb, indent);
      sb.append("\"ruleSetRef\": ").append(Json.escape(d.ruleSetRef())).append(",\n");
      appendIndent(sb, indent);
      sb.append("\"namedOutcomes\": ");
      serializeStrings(d.namedOutcomes(), sb, indent);
    } else if (activity instanceof ChildWorkflowActivity c) {
      sb.append(",\n");
      appendIndent(sb, indent);
      sb.append("\"childWorkflowKey\": ").append(Json.escape(c.childWorkflowKey())).append(",\n");
      appendIndent(sb, indent);
      sb.append("\"versionConstraint\": ").append(Json.escape(c.versionConstraint())).append(",\n");
      appendIndent(sb, indent);
      sb.append("\"correlationKeyMapping\": ")
          .append(Json.escape(c.correlationKeyMapping()))
          .append(",\n");
      appendIndent(sb, indent);
      sb.append("\"businessKeyMapping\": ")
          .append(Json.escape(c.businessKeyMapping()))
          .append(",\n");
      appendIndent(sb, indent);
      sb.append("\"duplicatePolicy\": ")
          .append(c.duplicatePolicy() == null ? "null" : Json.escape(c.duplicatePolicy().name()))
          .append(",\n");
      appendIndent(sb, indent);
      sb.append("\"parentWaitingBehavior\": ").append(Json.escape(c.parentWaitingBehavior()));
    } else if (activity instanceof WaitEventActivity w) {
      sb.append(",\n");
      appendIndent(sb, indent);
      sb.append("\"eventType\": ").append(Json.escape(w.eventType())).append(",\n");
      appendIndent(sb, indent);
      sb.append("\"correlationKeyMapping\": ")
          .append(Json.escape(w.correlationKeyMapping()))
          .append(",\n");
      appendIndent(sb, indent);
      sb.append("\"waitTimeoutRef\": ").append(Json.escape(w.waitTimeoutRef())).append(",\n");
      appendIndent(sb, indent);
      sb.append("\"timeoutTransition\": ").append(Json.escape(w.timeoutTransition()));
    } else if (activity instanceof TimerActivity t) {
      sb.append(",\n");
      appendIndent(sb, indent);
      sb.append("\"duration\": ").append(Json.escape(t.duration())).append(",\n");
      appendIndent(sb, indent);
      sb.append("\"durationPolicyRef\": ").append(Json.escape(t.durationPolicyRef())).append(",\n");
      appendIndent(sb, indent);
      sb.append("\"targetTransition\": ").append(Json.escape(t.targetTransition()));
    }
  }

  private static void serializeTransitions(
      List<Transition> transitions, StringBuilder sb, int indent) {
    sb.append("[\n");
    for (int i = 0; i < transitions.size(); i++) {
      Transition transition = transitions.get(i);
      appendIndent(sb, indent + 1);
      sb.append("{\n");
      appendIndent(sb, indent + 2);
      sb.append("\"sourceActivityKey\": ")
          .append(Json.escape(transition.sourceActivityKey()))
          .append(",\n");
      appendIndent(sb, indent + 2);
      sb.append("\"targetActivityKey\": ")
          .append(Json.escape(transition.targetActivityKey()))
          .append(",\n");
      appendIndent(sb, indent + 2);
      sb.append("\"outcome\": ").append(Json.escape(transition.outcome())).append(",\n");
      appendIndent(sb, indent + 2);
      sb.append("\"conditionRef\": ").append(Json.escape(transition.conditionRef())).append(",\n");
      appendIndent(sb, indent + 2);
      sb.append("\"priority\": ").append(transition.priority()).append(",\n");
      appendIndent(sb, indent + 2);
      sb.append("\"label\": ").append(Json.escape(transition.label()));
      sb.append("\n");
      appendIndent(sb, indent + 1);
      sb.append("}");
      if (i < transitions.size() - 1) {
        sb.append(",");
      }
      sb.append("\n");
    }
    appendIndent(sb, indent);
    sb.append("]");
  }

  private static void serializeStrings(List<String> values, StringBuilder sb, int indent) {
    sb.append("[");
    for (int i = 0; i < values.size(); i++) {
      sb.append(Json.escape(values.get(i)));
      if (i < values.size() - 1) {
        sb.append(", ");
      }
    }
    sb.append("]");
  }

  private static void serializeMetadata(
      Map<String, String> metadata, StringBuilder sb, int indent) {
    Map<String, String> sorted = new TreeMap<>(metadata);
    sb.append("{");
    boolean first = true;
    for (Map.Entry<String, String> entry : sorted.entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      sb.append(Json.escape(entry.getKey())).append(": ").append(Json.escape(entry.getValue()));
      first = false;
    }
    sb.append("}");
  }

  private static void appendIndent(StringBuilder sb, int indent) {
    for (int i = 0; i < indent; i++) {
      sb.append("  ");
    }
  }

  public static WorkflowDefinition deserialize(String json) {
    JsonValue value = Json.parse(json);
    if (!(value instanceof JsonObject root)) {
      throw new IllegalArgumentException("Workflow definition must be a JSON object");
    }
    List<ActivityDefinition> activities = deserializeActivities(root.requiredArray("activities"));
    List<Transition> transitions = deserializeTransitions(root.requiredArray("transitions"));
    return new WorkflowDefinition(
        root.requiredString("workflowKey"),
        root.requiredString("displayName"),
        root.string("description", ""),
        SemanticVersion.parse(root.requiredString("version")),
        WorkflowDefinitionStatus.valueOf(root.requiredString("status")),
        activities,
        transitions,
        root.requiredString("startActivityKey"),
        root.requiredStringList("terminalActivityKeys"),
        root.metadata("metadata"),
        Instant.parse(root.requiredString("createdAt")),
        root.requiredString("createdBy"),
        root.requiredString("correlationKeyDefinition"),
        root.requiredString("idempotencyKeyDefinition"));
  }

  private static List<ActivityDefinition> deserializeActivities(JsonArray array) {
    List<ActivityDefinition> activities = new ArrayList<>();
    for (JsonValue value : array.values()) {
      if (!(value instanceof JsonObject obj)) {
        throw new IllegalArgumentException("Activity must be a JSON object");
      }
      String typeName = obj.requiredString("type");
      ActivityType type;
      try {
        type = ActivityType.valueOf(typeName);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Unknown activity type: " + typeName);
      }
      ActivityDefinition activity =
          switch (type) {
            case START ->
                new StartActivity(
                    obj.requiredString("activityKey"),
                    obj.requiredString("displayName"),
                    obj.string("description", ""),
                    obj.string("inputSchemaRef", null),
                    obj.string("outputSchemaRef", null),
                    obj.string("requiredRoleRef", null),
                    obj.string("retryPolicyRef", null),
                    obj.string("timeoutRef", null),
                    obj.metadata("metadata"));
            case END ->
                new EndActivity(
                    obj.requiredString("activityKey"),
                    obj.requiredString("displayName"),
                    obj.string("description", ""),
                    obj.string("inputSchemaRef", null),
                    obj.string("outputSchemaRef", null),
                    obj.string("requiredRoleRef", null),
                    obj.string("retryPolicyRef", null),
                    obj.string("timeoutRef", null),
                    obj.metadata("metadata"));
            case HUMAN_TASK ->
                new HumanTaskActivity(
                    obj.requiredString("activityKey"),
                    obj.requiredString("displayName"),
                    obj.string("description", ""),
                    obj.string("inputSchemaRef", null),
                    obj.string("outputSchemaRef", null),
                    obj.string("requiredRoleRef", null),
                    obj.string("retryPolicyRef", null),
                    obj.string("timeoutRef", null),
                    obj.metadata("metadata"),
                    obj.requiredString("taskTypeKey"),
                    obj.requiredString("eligibleRoleKey"),
                    obj.requiredStringList("allowedOutcomes"),
                    obj.stringList("requiredEvidenceTypes"),
                    obj.string("assignmentPolicyRef", null),
                    obj.string("slaPolicyRef", null));
            case SERVICE_TASK ->
                new ServiceTaskActivity(
                    obj.requiredString("activityKey"),
                    obj.requiredString("displayName"),
                    obj.string("description", ""),
                    obj.string("inputSchemaRef", null),
                    obj.string("outputSchemaRef", null),
                    obj.string("requiredRoleRef", null),
                    obj.string("retryPolicyRef", null),
                    obj.string("timeoutRef", null),
                    obj.metadata("metadata"),
                    obj.requiredString("connectorKey"),
                    obj.requiredString("operationKey"),
                    obj.string("serviceRetryPolicyRef", null),
                    obj.bool("idempotencyRequired", false),
                    obj.string("inputContractRef", null),
                    obj.string("outputContractRef", null));
            case DECISION ->
                new DecisionActivity(
                    obj.requiredString("activityKey"),
                    obj.requiredString("displayName"),
                    obj.string("description", ""),
                    obj.string("inputSchemaRef", null),
                    obj.string("outputSchemaRef", null),
                    obj.string("requiredRoleRef", null),
                    obj.string("retryPolicyRef", null),
                    obj.string("timeoutRef", null),
                    obj.metadata("metadata"),
                    obj.requiredString("decisionKey"),
                    obj.requiredString("ruleSetRef"),
                    obj.requiredStringList("namedOutcomes"));
            case PARALLEL_SPLIT ->
                new ParallelSplitActivity(
                    obj.requiredString("activityKey"),
                    obj.requiredString("displayName"),
                    obj.string("description", ""),
                    obj.string("inputSchemaRef", null),
                    obj.string("outputSchemaRef", null),
                    obj.string("requiredRoleRef", null),
                    obj.string("retryPolicyRef", null),
                    obj.string("timeoutRef", null),
                    obj.metadata("metadata"));
            case PARALLEL_JOIN ->
                new ParallelJoinActivity(
                    obj.requiredString("activityKey"),
                    obj.requiredString("displayName"),
                    obj.string("description", ""),
                    obj.string("inputSchemaRef", null),
                    obj.string("outputSchemaRef", null),
                    obj.string("requiredRoleRef", null),
                    obj.string("retryPolicyRef", null),
                    obj.string("timeoutRef", null),
                    obj.metadata("metadata"));
            case CHILD_WORKFLOW ->
                new ChildWorkflowActivity(
                    obj.requiredString("activityKey"),
                    obj.requiredString("displayName"),
                    obj.string("description", ""),
                    obj.string("inputSchemaRef", null),
                    obj.string("outputSchemaRef", null),
                    obj.string("requiredRoleRef", null),
                    obj.string("retryPolicyRef", null),
                    obj.string("timeoutRef", null),
                    obj.metadata("metadata"),
                    obj.requiredString("childWorkflowKey"),
                    obj.string("versionConstraint", null),
                    obj.requiredString("correlationKeyMapping"),
                    obj.requiredString("businessKeyMapping"),
                    obj.enumValue("duplicatePolicy", DuplicatePolicy.class),
                    obj.string("parentWaitingBehavior", null));
            case WAIT_EVENT ->
                new WaitEventActivity(
                    obj.requiredString("activityKey"),
                    obj.requiredString("displayName"),
                    obj.string("description", ""),
                    obj.string("inputSchemaRef", null),
                    obj.string("outputSchemaRef", null),
                    obj.string("requiredRoleRef", null),
                    obj.string("retryPolicyRef", null),
                    obj.string("timeoutRef", null),
                    obj.metadata("metadata"),
                    obj.requiredString("eventType"),
                    obj.string("correlationKeyMapping", null),
                    obj.string("waitTimeoutRef", null),
                    obj.string("timeoutTransition", null));
            case TIMER ->
                new TimerActivity(
                    obj.requiredString("activityKey"),
                    obj.requiredString("displayName"),
                    obj.string("description", ""),
                    obj.string("inputSchemaRef", null),
                    obj.string("outputSchemaRef", null),
                    obj.string("requiredRoleRef", null),
                    obj.string("retryPolicyRef", null),
                    obj.string("timeoutRef", null),
                    obj.metadata("metadata"),
                    obj.string("duration", null),
                    obj.string("durationPolicyRef", null),
                    obj.string("targetTransition", null));
          };
      activities.add(activity);
    }
    return activities;
  }

  private static List<Transition> deserializeTransitions(JsonArray array) {
    List<Transition> transitions = new ArrayList<>();
    for (JsonValue value : array.values()) {
      if (!(value instanceof JsonObject obj)) {
        throw new IllegalArgumentException("Transition must be a JSON object");
      }
      transitions.add(
          new Transition(
              obj.requiredString("sourceActivityKey"),
              obj.requiredString("targetActivityKey"),
              obj.string("outcome", null),
              obj.string("conditionRef", null),
              obj.intValue("priority", 0),
              obj.string("label", "")));
    }
    return transitions;
  }

  private static final class Json {
    static JsonValue parse(String text) {
      Parser parser = new Parser(text);
      JsonValue value = parser.parseValue();
      parser.skipWhitespace();
      if (parser.hasMore()) {
        throw new IllegalArgumentException("Unexpected trailing content in JSON");
      }
      return value;
    }

    static String escape(String value) {
      if (value == null) {
        return "null";
      }
      StringBuilder sb = new StringBuilder();
      sb.append('"');
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        switch (c) {
          case '"' -> sb.append("\\\"");
          case '\\' -> sb.append("\\\\");
          case '\b' -> sb.append("\\b");
          case '\f' -> sb.append("\\f");
          case '\n' -> sb.append("\\n");
          case '\r' -> sb.append("\\r");
          case '\t' -> sb.append("\\t");
          default -> {
            if (c < 0x20) {
              sb.append(String.format("\\u%04x", (int) c));
            } else {
              sb.append(c);
            }
          }
        }
      }
      sb.append('"');
      return sb.toString();
    }
  }

  private static final class Parser {
    private final String text;
    private int pos;

    Parser(String text) {
      this.text = text;
      this.pos = 0;
    }

    boolean hasMore() {
      return pos < text.length();
    }

    void skipWhitespace() {
      while (pos < text.length()) {
        char c = text.charAt(pos);
        if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
          pos++;
        } else {
          break;
        }
      }
    }

    char peek() {
      if (!hasMore()) {
        throw new IllegalArgumentException("Unexpected end of JSON");
      }
      return text.charAt(pos);
    }

    char consume() {
      if (!hasMore()) {
        throw new IllegalArgumentException("Unexpected end of JSON");
      }
      return text.charAt(pos++);
    }

    void expect(char expected) {
      skipWhitespace();
      char c = consume();
      if (c != expected) {
        throw new IllegalArgumentException(
            "Expected '" + expected + "' but found '" + c + "' at position " + (pos - 1));
      }
    }

    JsonValue parseValue() {
      skipWhitespace();
      char c = peek();
      return switch (c) {
        case '{' -> parseObject();
        case '[' -> parseArray();
        case '"' -> new JsonString(parseString());
        case 't', 'f' -> new JsonBoolean(parseBoolean());
        case 'n' -> parseNull();
        case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> new JsonNumber(parseNumber());
        default -> throw new IllegalArgumentException("Unexpected character: " + c);
      };
    }

    JsonObject parseObject() {
      expect('{');
      Map<String, JsonValue> map = new LinkedHashMap<>();
      skipWhitespace();
      if (peek() == '}') {
        consume();
        return new JsonObject(map);
      }
      while (true) {
        skipWhitespace();
        String key = parseString();
        skipWhitespace();
        expect(':');
        JsonValue value = parseValue();
        map.put(key, value);
        skipWhitespace();
        char c = consume();
        if (c == '}') {
          break;
        }
        if (c != ',') {
          throw new IllegalArgumentException("Expected ',' or '}' in object");
        }
      }
      return new JsonObject(map);
    }

    JsonArray parseArray() {
      expect('[');
      List<JsonValue> list = new ArrayList<>();
      skipWhitespace();
      if (peek() == ']') {
        consume();
        return new JsonArray(list);
      }
      while (true) {
        JsonValue value = parseValue();
        list.add(value);
        skipWhitespace();
        char c = consume();
        if (c == ']') {
          break;
        }
        if (c != ',') {
          throw new IllegalArgumentException("Expected ',' or ']' in array");
        }
      }
      return new JsonArray(list);
    }

    String parseString() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (true) {
        char c = consume();
        if (c == '"') {
          break;
        }
        if (c == '\\') {
          char escaped = consume();
          switch (escaped) {
            case '"', '\\', '/' -> sb.append(escaped);
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'u' -> {
              String hex = text.substring(pos, pos + 4);
              sb.append((char) Integer.parseInt(hex, 16));
              pos += 4;
            }
            default -> throw new IllegalArgumentException("Invalid escape: \\" + escaped);
          }
        } else {
          sb.append(c);
        }
      }
      return sb.toString();
    }

    boolean parseBoolean() {
      if (text.startsWith("true", pos)) {
        pos += 4;
        return true;
      }
      if (text.startsWith("false", pos)) {
        pos += 5;
        return false;
      }
      throw new IllegalArgumentException("Invalid boolean literal");
    }

    JsonNull parseNull() {
      if (text.startsWith("null", pos)) {
        pos += 4;
        return new JsonNull();
      }
      throw new IllegalArgumentException("Invalid null literal");
    }

    String parseNumber() {
      int start = pos;
      if (peek() == '-') {
        consume();
      }
      while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
        pos++;
      }
      if (pos < text.length() && text.charAt(pos) == '.') {
        consume();
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
          pos++;
        }
      }
      if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
        consume();
        if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
          consume();
        }
        while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
          pos++;
        }
      }
      return text.substring(start, pos);
    }
  }

  private sealed interface JsonValue {}

  private record JsonObject(Map<String, JsonValue> values) implements JsonValue {
    JsonValue get(String key) {
      return values.get(key);
    }

    boolean has(String key) {
      return values.containsKey(key);
    }

    String requiredString(String key) {
      JsonValue value = values.get(key);
      if (value == null) {
        throw new IllegalArgumentException("Missing required field: " + key);
      }
      if (value instanceof JsonNull) {
        return null;
      }
      if (!(value instanceof JsonString s)) {
        throw new IllegalArgumentException("Field " + key + " must be a string");
      }
      return s.value();
    }

    String string(String key, String defaultValue) {
      JsonValue value = values.get(key);
      if (value == null) {
        return defaultValue;
      }
      if (value instanceof JsonNull) {
        return null;
      }
      if (!(value instanceof JsonString s)) {
        throw new IllegalArgumentException("Field " + key + " must be a string");
      }
      return s.value();
    }

    boolean bool(String key, boolean defaultValue) {
      JsonValue value = values.get(key);
      if (value == null) {
        return defaultValue;
      }
      if (!(value instanceof JsonBoolean b)) {
        throw new IllegalArgumentException("Field " + key + " must be a boolean");
      }
      return b.value();
    }

    int intValue(String key, int defaultValue) {
      JsonValue value = values.get(key);
      if (value == null) {
        return defaultValue;
      }
      if (!(value instanceof JsonNumber n)) {
        throw new IllegalArgumentException("Field " + key + " must be an integer");
      }
      return Integer.parseInt(n.value());
    }

    JsonArray requiredArray(String key) {
      JsonValue value = values.get(key);
      if (value == null) {
        throw new IllegalArgumentException("Missing required field: " + key);
      }
      if (!(value instanceof JsonArray a)) {
        throw new IllegalArgumentException("Field " + key + " must be an array");
      }
      return a;
    }

    List<String> requiredStringList(String key) {
      JsonArray array = requiredArray(key);
      List<String> result = new ArrayList<>();
      for (JsonValue value : array.values()) {
        if (value instanceof JsonNull) {
          result.add(null);
        } else if (value instanceof JsonString s) {
          result.add(s.value());
        } else {
          throw new IllegalArgumentException("Field " + key + " must contain strings");
        }
      }
      return result;
    }

    List<String> stringList(String key) {
      JsonValue value = values.get(key);
      if (value == null) {
        return List.of();
      }
      if (!(value instanceof JsonArray a)) {
        throw new IllegalArgumentException("Field " + key + " must be an array");
      }
      List<String> result = new ArrayList<>();
      for (JsonValue v : a.values()) {
        if (v instanceof JsonNull) {
          result.add(null);
        } else if (v instanceof JsonString s) {
          result.add(s.value());
        } else {
          throw new IllegalArgumentException("Field " + key + " must contain strings");
        }
      }
      return result;
    }

    Map<String, String> metadata(String key) {
      JsonValue value = values.get(key);
      if (value == null) {
        return Map.of();
      }
      if (!(value instanceof JsonObject o)) {
        throw new IllegalArgumentException("Field " + key + " must be an object");
      }
      Map<String, String> map = new LinkedHashMap<>();
      for (Map.Entry<String, JsonValue> entry : o.values().entrySet()) {
        if (entry.getValue() instanceof JsonNull) {
          map.put(entry.getKey(), null);
        } else if (entry.getValue() instanceof JsonString s) {
          map.put(entry.getKey(), s.value());
        } else {
          throw new IllegalArgumentException(
              "Metadata field " + entry.getKey() + " must be a string");
        }
      }
      return map;
    }

    <E extends Enum<E>> E enumValue(String key, Class<E> enumClass) {
      JsonValue value = values.get(key);
      if (value == null) {
        return null;
      }
      if (value instanceof JsonNull) {
        return null;
      }
      if (!(value instanceof JsonString s)) {
        throw new IllegalArgumentException("Field " + key + " must be a string enum");
      }
      return Enum.valueOf(enumClass, s.value());
    }
  }

  private record JsonArray(List<JsonValue> values) implements JsonValue {}

  private record JsonString(String value) implements JsonValue {}

  private record JsonNumber(String value) implements JsonValue {}

  private record JsonBoolean(boolean value) implements JsonValue {}

  private record JsonNull() implements JsonValue {}
}
