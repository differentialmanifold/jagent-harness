package io.github.differentialmanifold.jagentharness.core.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFilePaths;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolArguments;
import io.github.differentialmanifold.jagentharness.core.tool.support.ToolSchemas;

public class SkillTool implements ToolDefinition {

    private final ObjectMapper objectMapper;
    private final KnowledgeFileStore knowledgeFileStore;

    public SkillTool(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public SkillTool(ObjectMapper objectMapper, KnowledgeFileStore knowledgeFileStore) {
        this.objectMapper = objectMapper;
        this.knowledgeFileStore = knowledgeFileStore;
    }

    @Override
    public String getName() {
        return "skill";
    }

    @Override
    public String getDescription() {
        return "Load a skill instruction or resource. Use this tool for every path under skills/.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("path", ToolSchemas.stringProperty(
                objectMapper,
                "Skill resource path under skills/{skill}/. Use the SKILL.md path shown in Available skills. "
                        + "For relative references in SKILL.md, resolve them against the SKILL.md directory and pass the full resolved skill path to the skill tool."));
        return ToolSchemas.objectSchema(objectMapper, properties, "path");
    }

    @Override
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) throws Exception {
        String input = ToolArguments.requiredText(arguments, "path");
        String logicalPath = normalizeSkillPath(input);
        ToolExecutionResult databaseResult = readKnowledgeFileIfExists(context, logicalPath);
        if (databaseResult != null) {
            return databaseResult;
        }
        throw new IllegalArgumentException("Skill resource not found: " + input);
    }

    private String normalizeSkillPath(String input) {
        String path = KnowledgeFilePaths.normalize(input);
        String[] parts = path.split("/");
        if (parts.length < 3 || !"skills".equals(parts[0])) {
            throw new IllegalArgumentException(
                    "Skill resource path must be under skills/{skill}/: " + input);
        }
        return path;
    }

    private ToolExecutionResult readKnowledgeFileIfExists(ToolContext context, String input) {
        if (knowledgeFileStore == null) {
            return null;
        }
        KnowledgeFile file = null;
        if (context != null && context.getProjectId() != null && !context.getProjectId().trim().isEmpty()) {
            file = knowledgeFileStore.readFile(KnowledgeScope.project(context.getProjectId()), input);
        }
        if (file == null) {
            file = knowledgeFileStore.readFile(KnowledgeScope.global(), input);
        }
        if (file == null) {
            return null;
        }
        return skillResult(file.getPath(), file.getContent());
    }

    private ToolExecutionResult skillResult(String path, String content) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", path);
        result.put("type", "text");
        result.put("skillDirectory", KnowledgeFilePaths.parent(path));
        result.put("resourceTool", getName());
        result.put("resourceInstruction",
                "Resolve relative resource paths against skillDirectory and load them with the skill tool, not the read tool.");
        result.put("content", content);
        return ToolExecutionResult.of(result.toString());
    }

}
