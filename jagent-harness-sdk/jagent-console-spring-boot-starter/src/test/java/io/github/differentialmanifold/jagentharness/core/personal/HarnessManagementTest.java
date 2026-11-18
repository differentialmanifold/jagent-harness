package io.github.differentialmanifold.jagentharness.core.personal;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.identity.UserContext;
import io.github.differentialmanifold.jagentharness.core.model.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.project.*;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerService;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import io.github.differentialmanifold.jagentharness.spring.web.*;
import io.github.differentialmanifold.jagentharness.spring.web.personal.PersonalAgentController;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = TestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:harness-management;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "harness.store.jdbc.application-id=harness-management",
            "harness.model.base-url=https://model.example/v1",
            "harness.model.api-key=server-side-secret",
            "harness.model.model=normal",
            "harness.personal.learning.enabled=false",
            "harness.protocol.token=test-token"
        })
class HarnessManagementTest {
    @Autowired PersonalAgentService agents;
    @Autowired PersonalAgentStore store;
    @Autowired PersonalAgentTools personalTools;
    @Autowired PersonalAgentController agentController;
    @Autowired ProjectService projects;
    @Autowired RunnerService runners;
    @Autowired ToolRegistry tools;
    @Autowired ModelCatalog models;
    @Autowired ModelController modelController;
    @Autowired VirtualFileController fileController;
    @Autowired SessionController sessionController;
    @Autowired ObjectMapper json;
    UserContext user;
    ToolContext context;
    ToolDefinition tool;

    @BeforeEach
    void setUp() {
        user = UserContext.enter("management-" + UUID.randomUUID());
        agents.initializeMainbot();
        Conversation c =
                agents.newConversation(PersonalAgentService.MAINBOT_ID, "Manage my Harness");
        context = context(c.sessionId, PersonalAgentService.MAINBOT_ID);
        tool = tools.get("harness", context);
        assertNotNull(tool);
    }

    @AfterEach
    void tearDown() {
        user.close();
    }

    ToolContext context(String session, String agent) {
        return new ToolContext(
                session,
                "management-run",
                "turn",
                null,
                Collections.singletonMap("personalAgentId", agent));
    }

    JsonNode call(String operation, JsonNode arguments) throws Exception {
        ObjectNode input =
                json.createObjectNode().put("action", "execute").put("operation", operation);
        input.set("arguments", arguments);
        return json.readTree(tool.execute(context, input).getContent());
    }

    JsonNode call(String operation, String arguments) throws Exception {
        return call(operation, json.readTree(arguments));
    }

    @Test
    void mainbotAddsAndUpdatesVisionModelWithoutLosingDefaultsOrExposingKeys() throws Exception {
        String defaultId = models.publicConfiguration().defaultModelId;
        String schema = tool.getParametersSchema().toString();
        assertFalse(
                schema.contains("contextWindowTokens"), "Detailed operations are loaded on demand");
        JsonNode described =
                json.readTree(
                        tool.execute(
                                        context,
                                        json.readTree(
                                                "{\"action\":\"describe\",\"resource\":\"models\"}"))
                                .getContent());
        assertTrue(described.toString().contains("models.add"));
        ObjectNode request =
                (ObjectNode)
                        json.readTree(
                                "{\"revision\":0,\"model\":{\"id\":\"vision\",\"name\":\"Vision\",\"model\":\"vision-v1\",\"baseUrl\":\"https://model.example/v1\",\"inputTypes\":[\"text\",\"image\"]}}");
        request.put("credentialsFrom", defaultId);
        JsonNode added = call("models.add", request);
        assertEquals(defaultId, added.path("defaultModelId").asText());
        assertEquals(2, modelController.list().models.size());
        assertTrue(models.require("vision").inputTypes.contains("image"));
        assertEquals("server-side-secret", models.require("vision").apiKey);
        assertFalse(added.toString().contains("server-side-secret"));
        assertFalse(call("models.list", "{}").toString().contains("server-side-secret"));
        long revision = added.path("revision").asLong();
        call(
                "models.update",
                "{\"id\":\"vision\",\"revision\":"
                        + revision
                        + ",\"changes\":{\"name\":\"Pictures\"}}");
        assertEquals("Pictures", modelController.list().models.get(1).name);
        assertEquals("server-side-secret", models.require("vision").apiKey);
        assertThrows(
                IllegalArgumentException.class,
                () -> call("models.remove", "{\"id\":\"vision\",\"revision\":" + revision + "}"));
        assertEquals(2, modelController.list().models.size());
    }

    @Test
    void rejectsUnregisteredArgumentsWrongTypesAndCredentialExfiltration() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> call("models.list", "{\"userId\":\"someone-else\"}"));
        assertThrows(
                IllegalArgumentException.class,
                () -> call("models.add", "{\"revision\":\"0\",\"model\":{}}"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        call(
                                "models.add",
                                "{\"revision\":0,\"model\":{\"id\":\"secret\",\"name\":\"Secret\",\"model\":\"m\",\"apiKey\":\"raw-secret\"}}"));
        assertThrows(IllegalArgumentException.class, () -> call("http.execute", "{}"));
        ObjectNode request =
                (ObjectNode)
                        json.readTree(
                                "{\"revision\":0,\"model\":{\"id\":\"wrong\",\"name\":\"Wrong\",\"model\":\"m\",\"baseUrl\":\"https://elsewhere.example/v1\"}}");
        request.put("credentialsFrom", models.publicConfiguration().defaultModelId);
        assertThrows(IllegalArgumentException.class, () -> call("models.add", request));
        assertEquals(1, modelController.list().models.size());
    }

    @Test
    void onlyCurrentUsersMainbotCanDiscoverOrExecuteManagement() throws Exception {
        SessionRecord project = projects.createSession(null, new CreateSessionRequest());
        assertNull(tools.get("harness"));
        assertNull(tools.get("harness", new ToolContext(project.getSessionId(), "r", "t")));
        Profile other = new Profile();
        other.id = "other-agent";
        other.name = "Other";
        assertTrue(store.save(other));
        Conversation otherChat = agents.newConversation(other.id, "Other conversation");
        assertNull(tools.get("harness", context(otherChat.sessionId, other.id)));
        assertTrue(
                personalTools.catalog(other).stream()
                        .noneMatch(t -> "harness".equals(t.get("name"))));
        ObjectNode describe = json.createObjectNode().put("action", "describe");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        tool.execute(
                                context(otherChat.sessionId, PersonalAgentService.MAINBOT_ID),
                                describe));
        assertThrows(
                IllegalArgumentException.class,
                () -> tool.execute(context(otherChat.sessionId, other.id), describe));
        String session = context.getSessionId();
        call(
                "models.add",
                "{\"revision\":0,\"model\":{\"id\":\"private\",\"name\":\"Private model\",\"model\":\"private\"}}");
        try (UserContext ignored = UserContext.enter("another-user")) {
            agents.initializeMainbot();
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            tool.execute(
                                    context(session, PersonalAgentService.MAINBOT_ID), describe));
            Conversation c =
                    agents.newConversation(PersonalAgentService.MAINBOT_ID, "Another user");
            ToolContext otherUser = context(c.sessionId, PersonalAgentService.MAINBOT_ID);
            ObjectNode input =
                    json.createObjectNode()
                            .put("action", "execute")
                            .put("operation", "models.list");
            input.set("arguments", json.createObjectNode());
            JsonNode result =
                    json.readTree(
                            tools.get("harness", otherUser).execute(otherUser, input).getContent());
            assertEquals(1, result.path("models").size());
            assertThrows(
                    IllegalArgumentException.class, () -> projects.session(project.getSessionId()));
        }
        Profile mainbot = agents.require(PersonalAgentService.MAINBOT_ID);
        mainbot.disabledTools.add("harness_execute");
        agents.save(mainbot);
        assertThrows(IllegalArgumentException.class, () -> call("models.list", "{}"));
        assertNotNull(tool.execute(context, describe));
    }

    @Test
    void managementChangesAreVisibleThroughExistingConsoleServices() throws Exception {
        RunnerInfo runner = new RunnerInfo();
        runner.runnerId = "runner-one";
        runner.name = "Runner";
        ToolDescriptor read = new ToolDescriptor();
        read.name = "read";
        read.parameters.put("type", "object");
        runner.tools.add(read);
        runners.register(runner, "management-runner-secret-long-enough");
        JsonNode created =
                call("agents.create", "{\"name\":\"Helper\",\"runnerId\":\"runner-one\"}");
        String agent = created.path("id").asText();
        call(
                "agents.update",
                "{\"agentId\":\""
                        + agent
                        + "\",\"revision\":"
                        + created.path("revision").asLong()
                        + ",\"changes\":{\"description\":\"Configured by mainbot\"}}");
        assertTrue(
                agentController.agents().stream()
                        .anyMatch(
                                p ->
                                        p.id.equals(agent)
                                                && "Configured by mainbot".equals(p.description)));
        JsonNode project =
                call(
                        "projects.createSession",
                        "{\"title\":\"Human project chat\",\"projectName\":\"User project\"}");
        String session = project.path("sessionId").asText();
        assertTrue(
                sessionController.list().stream().anyMatch(s -> s.getSessionId().equals(session)));
        assertNull(projects.session(session).getOwnerAgentId());
        call(
                "projects.renameSession",
                "{\"sessionId\":\"" + session + "\",\"title\":\"Renamed chat\"}");
        assertEquals("Renamed chat", projects.session(session).getTitle());
        String skill =
                "---\nname: user-method\ndescription: A useful method\n---\nFollow the user's method.";
        ObjectNode write =
                json.createObjectNode()
                        .put("scope", "project")
                        .put("sessionId", session)
                        .put("path", "skills/user-method/SKILL.md")
                        .put("content", skill);
        call("knowledge.write", write);
        assertEquals(1, fileController.listFiles("skills", "project", session).size());
        assertEquals(0, fileController.listFiles("skills", "global", null).size());
        assertTrue(
                call("knowledge.list", "{\"scope\":\"project\",\"sessionId\":\"" + session + "\"}")
                        .toString()
                        .contains("user-method"));
        assertThrows(
                IllegalArgumentException.class,
                () -> call("knowledge.read", "{\"scope\":\"global\",\"path\":\"mcp.json\"}"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        call(
                                "knowledge.write",
                                "{\"scope\":\"global\",\"path\":\"skills/../mcp.json\",\"content\":\"bad\"}"));
    }
}
