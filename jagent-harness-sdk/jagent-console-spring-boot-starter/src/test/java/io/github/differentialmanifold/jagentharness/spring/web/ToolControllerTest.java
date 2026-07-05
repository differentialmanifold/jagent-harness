package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.agent.AgentRunOptions;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.session.SessionDetails;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.tool.KnowledgeFileToolConfiguration;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContextFactory;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ToolCallRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ToolConfigRequest;
import org.junit.jupiter.api.Test;

class ToolControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MemoryKnowledgeFileStore fileStore = new MemoryKnowledgeFileStore();
    private final KnowledgeFileToolConfiguration configuration =
            new KnowledgeFileToolConfiguration(fileStore, objectMapper);
    private final ToolRegistry registry = new ToolRegistry(
            Arrays.<ToolDefinition>asList(new EchoTool("read"), new EchoTool("bash")),
            Collections.emptyList(),
            Collections.singletonList(configuration));
    private final ToolController controller = new ToolController(
            registry,
            configuration,
            sessionManager(),
            toolContextFactory(),
            objectMapper);

    @Test
    void defaultsToAllToolsAndSavesAnExplicitSelection() {
        assertFalse(controller.config().isConfigured());
        assertEquals(Arrays.asList("read", "bash"), controller.config().getEnabledTools());

        ToolConfigRequest request = new ToolConfigRequest();
        request.setEnabledTools(Collections.singletonList("read"));

        assertTrue(controller.save(request).isConfigured());
        assertEquals(Collections.singletonList("read"), controller.config().getEnabledTools());
        assertEquals(1, registry.all().size());

        assertFalse(controller.delete().isConfigured());
        assertEquals(2, registry.all().size());
    }

    @Test
    void rejectsUnknownConfiguredTools() {
        ToolConfigRequest request = new ToolConfigRequest();
        request.setEnabledTools(Collections.singletonList("missing"));

        assertThrows(IllegalArgumentException.class, () -> controller.save(request));
    }

    @Test
    void debugsDisabledToolWithSelectedSessionWorkspace() throws Exception {
        ToolConfigRequest config = new ToolConfigRequest();
        config.setEnabledTools(Collections.<String>emptyList());
        controller.save(config);
        ToolCallRequest request = new ToolCallRequest();
        request.setSessionId("session-1");
        request.setToolName("read");
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("value", "hello");
        request.setArguments(arguments);

        String result = controller.call(request).getResult();

        assertTrue(result.contains("/workspace/example"));
        assertTrue(result.contains("hello"));
    }

    private SessionManager sessionManager() {
        return new SessionManager() {
            @Override
            public SessionRecord createSession(String title, String workspacePath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SessionRecord requireSession(String sessionId) {
                if (!"session-1".equals(sessionId)) {
                    throw new IllegalArgumentException("Session not found: " + sessionId);
                }
                SessionRecord session = new SessionRecord();
                session.setSessionId(sessionId);
                session.setWorkspacePath("/workspace/example");
                return session;
            }

            @Override
            public List<SessionRecord> listSessions() {
                return Collections.emptyList();
            }

            @Override
            public SessionDetails getDetails(String sessionId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SessionRecord renameSession(String sessionId, String title) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deleteSession(String sessionId) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private ToolContextFactory toolContextFactory() {
        return new ToolContextFactory() {
            @Override
            public ToolContext create(SessionRecord session, String turnId, AgentRunOptions options) {
                return new ToolContext(
                        session == null ? null : session.getSessionId(),
                        turnId,
                        session == null ? null : Paths.get(session.getWorkspacePath()));
            }
        };
    }

    private static class EchoTool implements ToolDefinition {

        private final String name;

        private EchoTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "Echo debug context";
        }

        @Override
        public JsonNode getParametersSchema() {
            return null;
        }

        @Override
        public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
            return ToolExecutionResult.of(context.getWorkspaceRoot() + ":" + arguments.path("value").asText());
        }
    }

    private static class MemoryKnowledgeFileStore implements KnowledgeFileStore {

        private final Map<String, KnowledgeFile> files = new LinkedHashMap<String, KnowledgeFile>();

        @Override
        public KnowledgeFile readFile(String path) {
            return files.get(path);
        }

        @Override
        public List<KnowledgeFile> listFiles(String prefix) {
            return new ArrayList<KnowledgeFile>(files.values());
        }

        @Override
        public KnowledgeFile writeFile(String path, String content, String contentType) {
            KnowledgeFile file = new KnowledgeFile(
                    path,
                    KnowledgeFile.TYPE_FILE,
                    content,
                    contentType,
                    Instant.now(),
                    Instant.now());
            files.put(path, file);
            return file;
        }

        @Override
        public void deleteFile(String path) {
            files.remove(path);
        }
    }
}
