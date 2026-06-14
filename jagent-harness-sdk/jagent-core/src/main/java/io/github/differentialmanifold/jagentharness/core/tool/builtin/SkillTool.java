package io.github.differentialmanifold.jagentharness.core.tool.builtin;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFilePaths;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
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
        Path rawPath = Paths.get(input);
        String logicalPath = rawPath.isAbsolute()
                ? logicalPath(context, rawPath)
                : normalizeSkillPath(input);

        ToolExecutionResult databaseResult = readKnowledgeFileIfExists(logicalPath);
        if (databaseResult != null) {
            return databaseResult;
        }

        Path path = rawPath.isAbsolute()
                ? resolveAbsolute(context, rawPath)
                : resolveRelative(context, logicalPath);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Skill resource not found: " + input);
        }

        String content = readUtf8Text(path, Files.readAllBytes(path));
        return skillResult(logicalPath, content);
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

    private ToolExecutionResult readKnowledgeFileIfExists(String input) {
        if (knowledgeFileStore == null) {
            return null;
        }
        KnowledgeFile file = knowledgeFileStore.readFile(input);
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

    private Path resolveRelative(ToolContext context, String logicalPath) {
        for (Path root : skillRoots(context)) {
            Path resolved = root.resolve(logicalPath).normalize();
            if (Files.isRegularFile(resolved)) {
                return resolved;
            }
        }
        throw new IllegalArgumentException("Skill resource not found: " + logicalPath);
    }

    private Path resolveAbsolute(ToolContext context, Path input) {
        Path resolved = input.toAbsolutePath().normalize();
        for (Path root : skillRoots(context)) {
            Path skillsRoot = root.resolve("skills").normalize();
            if (resolved.startsWith(skillsRoot)) {
                return resolved;
            }
        }
        throw new IllegalArgumentException("Path is outside configured skill roots: " + input);
    }

    private String logicalPath(ToolContext context, Path input) {
        Path resolved = input.toAbsolutePath().normalize();
        for (Path root : skillRoots(context)) {
            if (resolved.startsWith(root)) {
                String logicalPath = root.relativize(resolved).toString();
                return normalizeSkillPath(logicalPath);
            }
        }
        throw new IllegalArgumentException("Path is outside configured skill roots: " + input);
    }

    private List<Path> skillRoots(ToolContext context) {
        List<Path> roots = new ArrayList<Path>();
        if (context != null && context.getWorkspaceRoot() != null) {
            roots.add(context.getWorkspaceRoot().toAbsolutePath().normalize());
        }
        if (context != null && context.getConfigRoot() != null) {
            roots.add(context.getConfigRoot().toAbsolutePath().normalize());
        }
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("No project or global skill root is configured.");
        }
        return roots;
    }

    private String readUtf8Text(Path path, byte[] bytes) {
        if (containsNullByte(bytes)) {
            throw new IllegalArgumentException("Skill tool supports UTF-8 text files only: " + path.getFileName());
        }
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Skill tool supports UTF-8 text files only: " + path.getFileName());
        }
    }

    private boolean containsNullByte(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }
}
