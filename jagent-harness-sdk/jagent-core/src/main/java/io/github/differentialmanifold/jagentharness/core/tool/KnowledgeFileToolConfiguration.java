package io.github.differentialmanifold.jagentharness.core.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;

public class KnowledgeFileToolConfiguration implements ToolAvailabilityPolicy {

    public static final String CONFIG_PATH = "tools.json";

    private final KnowledgeFileStore knowledgeFileStore;
    private final ObjectMapper objectMapper;

    public KnowledgeFileToolConfiguration(KnowledgeFileStore knowledgeFileStore, ObjectMapper objectMapper) {
        if (knowledgeFileStore == null) {
            throw new IllegalArgumentException("knowledgeFileStore must not be null");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("objectMapper must not be null");
        }
        this.knowledgeFileStore = knowledgeFileStore;
        this.objectMapper = objectMapper;
    }

    public ToolSelectionSnapshot load() {
        KnowledgeFile file = knowledgeFileStore.readFile(KnowledgeScope.global(), CONFIG_PATH);
        if (file == null) {
            return ToolSelectionSnapshot.defaults();
        }
        try {
            JsonNode root = objectMapper.readTree(file.getContent());
            JsonNode enabledTools = root == null ? null : root.get("enabledTools");
            if (root == null || !root.isObject() || enabledTools == null || !enabledTools.isArray()) {
                throw invalidConfiguration();
            }
            Set<String> names = new LinkedHashSet<String>();
            for (JsonNode value : enabledTools) {
                if (!value.isTextual() || value.asText().trim().isEmpty()) {
                    throw invalidConfiguration();
                }
                names.add(value.asText().trim());
            }
            return new ToolSelectionSnapshot(true, names);
        } catch (IOException e) {
            throw new IllegalStateException("Invalid " + CONFIG_PATH + ": " + e.getMessage(), e);
        }
    }

    public ToolSelectionSnapshot save(Collection<String> enabledTools) {
        Set<String> names = new LinkedHashSet<String>();
        if (enabledTools != null) {
            for (String name : enabledTools) {
                String normalized = name == null ? "" : name.trim();
                if (normalized.isEmpty()) {
                    throw new IllegalArgumentException("Enabled tool names must not be blank");
                }
                names.add(normalized);
            }
        }
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode values = root.putArray("enabledTools");
        for (String name : names) {
            values.add(name);
        }
        try {
            knowledgeFileStore.writeFile(
                    KnowledgeScope.global(),
                    CONFIG_PATH,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root),
                    "application/json");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize " + CONFIG_PATH, e);
        }
        return new ToolSelectionSnapshot(true, names);
    }

    public void delete() {
        knowledgeFileStore.deleteFile(KnowledgeScope.global(), CONFIG_PATH);
    }

    @Override
    public Collection<ToolDefinition> filter(Collection<ToolDefinition> tools, AgentContext context) {
        ToolSelectionSnapshot selection = load();
        if (!selection.isConfigured()) {
            return tools;
        }
        Collection<ToolDefinition> available = new ArrayList<ToolDefinition>();
        if (tools != null) {
            for (ToolDefinition tool : tools) {
                if (tool != null && selection.isEnabled(tool.getName())) {
                    available.add(tool);
                }
            }
        }
        return Collections.unmodifiableCollection(available);
    }

    private IllegalStateException invalidConfiguration() {
        return new IllegalStateException(
                "Invalid " + CONFIG_PATH + ": enabledTools must be an array of non-empty strings");
    }
}
