package io.github.differentialmanifold.jagentharness.core.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.fs.TestKnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillToolTest {

    @TempDir
    Path workspaceRoot;

    @TempDir
    Path configRoot;

    @Test
    void readsProjectSkillByLogicalPath() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TestKnowledgeFileStore store = new TestKnowledgeFileStore();
        store.writeFile(KnowledgeScope.project("project-1"), "skills/java-review/SKILL.md", "# Project Review\n", "text/markdown");
        SkillTool tool = new SkillTool(objectMapper, store);

        JsonNode result = execute(objectMapper, tool, "skills/java-review/SKILL.md");

        assertEquals("skills/java-review/SKILL.md", result.path("path").asText());
        assertEquals("text", result.path("type").asText());
        assertEquals("skills/java-review", result.path("skillDirectory").asText());
        assertEquals("skill", result.path("resourceTool").asText());
        assertTrue(result.path("resourceInstruction").asText().contains("not the read tool"));
        assertEquals("# Project Review\n", result.path("content").asText());
    }

    @Test
    void projectSkillOverridesGlobalSkillWithSamePath() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TestKnowledgeFileStore store = new TestKnowledgeFileStore();
        store.writeFile(KnowledgeScope.global(), "skills/java-review/SKILL.md", "# Global Review\n", "text/markdown");
        store.writeFile(KnowledgeScope.project("project-1"), "skills/java-review/SKILL.md", "# Project Review\n", "text/markdown");
        SkillTool tool = new SkillTool(objectMapper, store);

        JsonNode result = execute(objectMapper, tool, "skills/java-review/SKILL.md");

        assertEquals("# Project Review\n", result.path("content").asText());
    }

    @Test
    void fallsBackToGlobalSkillForLogicalPath() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TestKnowledgeFileStore store = new TestKnowledgeFileStore();
        store.writeFile(KnowledgeScope.global(), "skills/java-review/SKILL.md", "# Global Review\n", "text/markdown");
        SkillTool tool = new SkillTool(objectMapper, store);

        JsonNode result = execute(objectMapper, tool, "skills/java-review/SKILL.md");

        assertEquals("# Global Review\n", result.path("content").asText());
    }

    @Test
    void rejectsAbsoluteSkillResourcePath() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SkillTool tool = new SkillTool(objectMapper, new TestKnowledgeFileStore());
        Path resource = configRoot.resolve("skills/java-review/checklist.md");
        write(resource, "# Checklist\n");

        assertThrows(IllegalArgumentException.class, () -> execute(objectMapper, tool, resource.toString()));
    }

    @Test
    void rejectsOrdinaryWorkspaceFile() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SkillTool tool = new SkillTool(objectMapper, new TestKnowledgeFileStore());
        write(workspaceRoot.resolve("example.py"), "print('hello')\n");

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "example.py");

        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments));
    }

    @Test
    void rejectsAbsolutePathOutsideSkillRoots() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SkillTool tool = new SkillTool(objectMapper, new TestKnowledgeFileStore());
        Path file = workspaceRoot.resolve("README.md");
        write(file, "outside skills\n");

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", file.toString());

        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments));
    }

    @Test
    void rejectsNonTextSkillResource() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SkillTool tool = new SkillTool(objectMapper, new TestKnowledgeFileStore());
        Path file = workspaceRoot.resolve("skills/review/blob.bin");
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] { 0, 1, 2, 3 });

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "skills/review/blob.bin");

        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(toolContext(), arguments));
    }

    @Test
    void readsDatabaseSkillFileByLogicalPath() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TestKnowledgeFileStore store = new TestKnowledgeFileStore();
        store.writeFile("skills/review/SKILL.md", "# Review\n", "text/markdown");
        SkillTool tool = new SkillTool(objectMapper, store);

        JsonNode result = execute(objectMapper, tool, "skills/review/SKILL.md");

        assertEquals("skills/review/SKILL.md", result.path("path").asText());
        assertEquals("# Review\n", result.path("content").asText());
    }

    @Test
    void projectDatabaseSkillOverridesGlobalDatabaseSkillWithSamePath() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TestKnowledgeFileStore store = new TestKnowledgeFileStore();
        store.writeFile(KnowledgeScope.global(), "skills/review/SKILL.md", "# Global Review\n", "text/markdown");
        store.writeFile(KnowledgeScope.project("project-1"), "skills/review/SKILL.md", "# Project Review\n", "text/markdown");
        SkillTool tool = new SkillTool(objectMapper, store);

        JsonNode result = execute(objectMapper, tool, "skills/review/SKILL.md");

        assertEquals("# Project Review\n", result.path("content").asText());
    }

    private JsonNode execute(ObjectMapper objectMapper, SkillTool tool, String path) throws Exception {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", path);
        ToolExecutionResult executionResult = tool.execute(toolContext(), arguments);
        return objectMapper.readTree(executionResult.getContent());
    }

    private ToolContext toolContext() {
        return new ToolContext(
                "session",
                "turn",
                null,
                workspaceRoot,
                configRoot,
                null,
                io.github.differentialmanifold.jagentharness.core.agent.StopSignal.none(),
                io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalMode.FULL_ACCESS,
                null,
                null,
                null,
                "project-1");
    }

    private void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
