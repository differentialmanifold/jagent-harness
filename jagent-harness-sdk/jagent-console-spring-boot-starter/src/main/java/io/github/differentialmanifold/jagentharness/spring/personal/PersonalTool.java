package io.github.differentialmanifold.jagentharness.spring.personal;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;

/** Shared identity, permission and parameter checks for personal tools. */
abstract class PersonalTool implements ToolDefinition {
    final ObjectProvider<PersonalAgentService> services;
    final PersonalAgentStore store;
    final ObjectMapper json;
    private final String name, description;
    private final Map<String, Action> actions = new LinkedHashMap<>();

    PersonalTool(
            String name,
            String description,
            ObjectProvider<PersonalAgentService> services,
            PersonalAgentStore store,
            ObjectMapper json) {
        this.name = name;
        this.description = description;
        this.services = services;
        this.store = store;
        this.json = json;
    }

    static String identity(PersonalAgentStore store, AgentContext context) {
        String id = context == null ? null : context.getAttribute("personalAgentId", String.class);
        Conversation c = context == null ? null : store.conversation(context.getSessionId());
        return c != null && c.agentId.equals(id) ? id : null;
    }

    public final String getName() {
        return name;
    }

    public final String getDescription() {
        return description;
    }

    public final JsonNode getParametersSchema() {
        return schema(actions.keySet());
    }

    final void action(
            String name,
            String description,
            ObjectNode fields,
            String[] legacy,
            String... required) {
        actions.put(name, new Action(description, fields, legacy, required));
    }

    private boolean enabled(Profile profile, String action) {
        List<String> disabled =
                profile.disabledTools == null ? Collections.emptyList() : profile.disabledTools;
        if (disabled.contains(name) || disabled.contains(name + "_" + action)) return false;
        for (String key : actions.get(action).legacy) if (disabled.contains(key)) return false;
        return true;
    }

    final ToolDefinition forProfile(Profile profile) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String action : actions.keySet()) if (enabled(profile, action)) allowed.add(action);
        if (allowed.isEmpty()) return null;
        JsonNode schema = schema(allowed);
        return new ToolDefinition() {
            public String getName() {
                return name;
            }

            public String getDescription() {
                return description;
            }

            public JsonNode getParametersSchema() {
                return schema;
            }

            public ToolExecutionResult execute(ToolContext context, JsonNode args)
                    throws Exception {
                return PersonalTool.this.execute(context, args);
            }
        };
    }

    final Map<String, Object> catalog(Profile profile) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("description", description);
        out.put("parametersSchema", getParametersSchema());
        out.put("enabled", profile.disabledTools == null || !profile.disabledTools.contains(name));
        List<Map<String, Object>> rows = new ArrayList<>();
        actions.forEach(
                (key, action) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", key);
                    row.put("description", action.description);
                    row.put("enabled", enabled(profile, key));
                    Set<String> keys = new LinkedHashSet<>(Arrays.asList(action.legacy));
                    keys.add(name + "_" + key);
                    row.put("disabledKeys", keys);
                    rows.add(row);
                });
        out.put("actions", rows);
        return out;
    }

    private ObjectNode schema(Set<String> allowed) {
        ObjectNode properties = json.createObjectNode();
        ObjectNode selector =
                text(
                        "Choose an operation. "
                                + String.join(
                                        " ",
                                        allowed.stream()
                                                .map(
                                                        key -> {
                                                            Action a = actions.get(key);
                                                            return key
                                                                    + ": "
                                                                    + a.description
                                                                    + (a.required.length == 0
                                                                            ? ""
                                                                            : " Required: "
                                                                                    + String.join(
                                                                                            ", ",
                                                                                            a.required)
                                                                                    + ".");
                                                        })
                                                .collect(java.util.stream.Collectors.toList())));
        ArrayNode values = selector.putArray("enum");
        allowed.forEach(values::add);
        properties.set("action", selector);
        for (String key : allowed)
            actions.get(key)
                    .fields
                    .fields()
                    .forEachRemaining(field -> properties.set(field.getKey(), field.getValue()));
        return ToolSchemas.objectSchema(json, properties, "action");
    }

    public final ToolExecutionResult execute(ToolContext context, JsonNode args) throws Exception {
        String id = identity(store, context);
        if (id == null)
            throw new IllegalArgumentException(
                    "Only this Agent's conversations can use personal tools");
        Profile p = services.getObject().require(id);
        if (!p.enabled) throw new IllegalArgumentException("Agent is paused");
        if (args == null || !args.isObject())
            throw new IllegalArgumentException("Arguments must be an object");
        String action = required(args, "action");
        Action contract = actions.get(action);
        if (contract == null)
            throw new IllegalArgumentException(
                    "Unknown action; choose " + String.join(", ", actions.keySet()));
        if (!enabled(p, action)) throw new IllegalArgumentException("This operation is disabled");
        validate(args, contract);
        context.getStopSignal().throwIfAborted();
        return ToolExecutionResult.of(json.writeValueAsString(run(id, context, action, args)));
    }

    private void validate(JsonNode args, Action a) {
        for (String key : a.required)
            if (!args.hasNonNull(key))
                throw new IllegalArgumentException("Missing required field: " + key);
        args.fields()
                .forEachRemaining(
                        field -> {
                            if ("action".equals(field.getKey())) return;
                            JsonNode schema = a.fields.get(field.getKey());
                            if (schema == null)
                                throw new IllegalArgumentException(
                                        "Unexpected field for this action: " + field.getKey());
                            validateValue(field.getValue(), schema, field.getKey());
                        });
    }

    private void validateValue(JsonNode value, JsonNode schema, String path) {
        String type = schema.path("type").asText();
        if (value.isNull()
                || ("string".equals(type) && !value.isTextual())
                || ("integer".equals(type) && !value.isIntegralNumber())
                || ("boolean".equals(type) && !value.isBoolean())
                || ("array".equals(type) && !value.isArray())
                || ("object".equals(type) && !value.isObject()))
            throw new IllegalArgumentException("Invalid field type: " + path);
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode option : schema.get("enum")) if (option.equals(value)) found = true;
            if (!found) throw new IllegalArgumentException("Invalid choice: " + path);
        }
        if (value.isArray())
            for (JsonNode item : value) validateValue(item, schema.path("items"), path + "[]");
        if (value.isObject()) {
            for (JsonNode key : schema.path("required"))
                if (!value.hasNonNull(key.asText()))
                    throw new IllegalArgumentException(
                            "Missing required field: " + path + "." + key.asText());
            value.fields()
                    .forEachRemaining(
                            field -> {
                                JsonNode child = schema.path("properties").get(field.getKey());
                                if (child == null)
                                    throw new IllegalArgumentException(
                                            "Unexpected field: " + path + "." + field.getKey());
                                validateValue(field.getValue(), child, path + "." + field.getKey());
                            });
        }
    }

    final ObjectNode array(JsonNode item) {
        ObjectNode n = json.createObjectNode().put("type", "array");
        n.set("items", item);
        return n;
    }

    abstract Object run(String id, ToolContext context, String action, JsonNode args)
            throws Exception;

    final ObjectNode text(String description) {
        return ToolSchemas.stringProperty(json, description);
    }

    final ObjectNode number(String description) {
        return ToolSchemas.integerProperty(json, description);
    }

    final ObjectNode choice(String description, String... choices) {
        ObjectNode n = text(description);
        ArrayNode a = n.putArray("enum");
        for (String c : choices) a.add(c);
        return n;
    }

    final ObjectNode fields(Object... pairs) {
        ObjectNode n = json.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) n.set((String) pairs[i], (JsonNode) pairs[i + 1]);
        return n;
    }

    final ObjectNode editFields(Object... pairs) {
        ObjectNode n = fields(pairs);
        n.set("reason", text("Why this change is supported by evidence."));
        n.set(
                "revision",
                number(
                        "Current shared profile revision from memory read, skills read, or the previous edit."));
        n.set("messageId", text("Optional evidence message in this conversation."));
        return n;
    }

    static String required(JsonNode n, String key) {
        return PersonalAgentService.text(n.path(key).asText(null), key, 32000);
    }

    private static final class Action {
        final String description;
        final ObjectNode fields;
        final String[] legacy, required;

        Action(String d, ObjectNode f, String[] l, String[] r) {
            description = d;
            fields = f;
            legacy = l;
            required = r;
        }
    }
}
