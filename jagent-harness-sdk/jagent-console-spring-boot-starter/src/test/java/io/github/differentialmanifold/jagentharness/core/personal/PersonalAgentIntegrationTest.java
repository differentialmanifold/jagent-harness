package io.github.differentialmanifold.jagentharness.core.personal;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.execution.*;
import io.github.differentialmanifold.jagentharness.core.fs.*;
import io.github.differentialmanifold.jagentharness.core.message.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.project.CreateSessionRequest;
import io.github.differentialmanifold.jagentharness.core.project.ProjectService;
import io.github.differentialmanifold.jagentharness.core.provider.*;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerService;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import io.github.differentialmanifold.jagentharness.spring.web.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        classes = TestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:personal-tests;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "harness.model.provider=personal-test",
            "harness.model.model=test",
            "harness.model.retry.enabled=false",
            "harness.compaction.enabled=false",
            "harness.protocol.token=test-token",
            "harness.store.jdbc.application-id=personal-tests"
        })
@Import(PersonalAgentIntegrationTest.Fixtures.class)
class PersonalAgentIntegrationTest {
    @Autowired PersonalAgentService service;
    @Autowired PersonalAgentKnowledge knowledge;
    @Autowired PersonalAgentLearning learning;
    @Autowired PersonalAgentStore store;
    @Autowired SessionManager sessions;
    @Autowired SessionRepository records;
    @Autowired SessionStore history;
    @Autowired KnowledgeFileStore files;
    @Autowired ToolRegistry tools;
    @Autowired AgentHarness harness;
    @Autowired ConversationService chat;
    @Autowired ProjectService projects;
    @Autowired RunService runs;
    @Autowired RunnerService runners;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired Model model;
    @Autowired SessionController sessionController;
    @Autowired AgentContextController contextController;
    @Autowired PromptPreviewController promptController;
    @org.springframework.boot.web.server.LocalServerPort int port;

    @Autowired io.github.differentialmanifold.jagentharness.core.model.ModelCatalog modelCatalog;

    @Autowired
    io.github.differentialmanifold.jagentharness.spring.web.personal.PersonalAgentController
            personalController;

    @Test
    void primaryDefaultsPersistWhilePeerCreationKeepsItsOwnEnvironment() throws Exception {
        String original = store.primaryAgentId();
        List<Profile> created = new ArrayList<>();
        for (String name : Arrays.asList("Primary machine", "Other machine")) {
            RunnerInfo runner = new RunnerInfo();
            runner.runnerId = "runner_" + UUID.randomUUID().toString().replace("-", "");
            runner.name = name;
            ToolDescriptor tool = new ToolDescriptor();
            tool.name = "read";
            tool.parameters.put("type", "object");
            runner.tools.add(tool);
            runners.register(runner, "a-test-secret-long-enough-for-registration");
            Profile p = new Profile();
            p.name = name;
            p.runnerId = runner.runnerId;
            created.add(service.save(p));
        }
        Profile primary = created.get(0), other = created.get(1);
        try {
            service.selectPrimaryAgent(primary.id, store.primaryAgentId());
            assertEquals(primary.runnerId, personalController.defaults().get("primaryRunnerId"));
            Profile humanCreated = new Profile();
            humanCreated.name = "User's assistant";
            personalController.save(humanCreated);
            assertEquals(primary.runnerId, humanCreated.runnerId);
            Conversation c = service.newConversation(other.id, "Create a colleague");
            ToolContext context =
                    new ToolContext(
                            c.sessionId,
                            "run",
                            "turn",
                            null,
                            Collections.singletonMap("personalAgentId", other.id));
            JsonNode peer =
                    json.readTree(
                            tools.get("agents", context)
                                    .execute(
                                            context,
                                            json.createObjectNode()
                                                    .put("action", "create")
                                                    .put("name", "Local colleague")
                                                    .put("description", "Same environment"))
                                    .getContent());
            assertEquals(other.runnerId, peer.path("runnerId").asText());
            service.selectPrimaryAgent(other.id, primary.id);
            assertEquals(other.runnerId, personalController.defaults().get("primaryRunnerId"));
            assertEquals(primary.runnerId, store.profile(humanCreated.id).runnerId);
            assertEquals(primary.runnerId, store.profile(primary.id).runnerId);
            assertThrows(
                    PersonalAgentException.class,
                    () -> service.selectPrimaryAgent(primary.id, primary.id));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.selectPrimaryAgent(profile("Unbound").id, other.id));
        } finally {
            if (original != null) store.setPrimaryAgentId(original, store.primaryAgentId());
        }
    }

    @Test
    void noModelToolsAreExposedAndProjectsUseUserRequestedNamesOrDefault() throws Exception {
        Profile p = profile("Model planner");
        Conversation c = service.newConversation(p.id, "Choose execution models");
        SessionRecord project = service.createProjectSession(p.id, new CreateSessionRequest());
        ToolContext personal =
                new ToolContext(
                        c.sessionId,
                        "selection-test",
                        "turn",
                        null,
                        Collections.singletonMap("personalAgentId", p.id));
        ToolContext projectContext =
                new ToolContext(
                        project.getSessionId(), "work", "turn", null, Collections.emptyMap());
        assertNull(tools.get("models", personal));
        assertNull(tools.get("models", projectContext));
        assertNull(tools.get("models"));
        assertTrue(
                tools.get("projects", personal)
                        .getParametersSchema()
                        .path("properties")
                        .has("modelId"));
        io.github.differentialmanifold.jagentharness.core.model.ModelCatalogStore.Configuration
                config = modelCatalog.publicConfiguration();
        io.github.differentialmanifold.jagentharness.core.model.ModelProfile worker =
                config.models.get(0).copy();
        worker.id = "project-selection-test";
        worker.name = "worker";
        worker.model = "worker-model";
        worker.temperature = 0.2;
        config.models.add(worker);
        modelCatalog.save(config);
        p.enabled = false;
        service.save(p);
        Card task = new Card();
        task.title = "Check chosen model";
        task.acceptance = "Report evidence";
        task.conversationId = c.sessionId;
        service.saveCard(task, p.id);
        task.modelId = "planner-model";
        store.save(task);
        Inbox watch =
                service.submitProject(
                        p.id,
                        c.sessionId,
                        project.getSessionId(),
                        "selected-model-probe",
                        null,
                        task.id,
                        true,
                        worker.model);
        assertEquals(worker.id, watch.projectModelId);
        assertEquals("planner-model", watch.modelId);
        await(
                () ->
                        model.requests.stream()
                                .anyMatch(
                                        request ->
                                                "worker-model".equals(request.getModel())
                                                        && request.getMessages().stream()
                                                                .anyMatch(
                                                                        m ->
                                                                                m.getContent()
                                                                                                != null
                                                                                        && m.getContent()
                                                                                                .contains(
                                                                                                        "selected-model-probe"))));
        ModelRequest chosen =
                model.requests.stream()
                        .filter(request -> "worker-model".equals(request.getModel()))
                        .findFirst()
                        .get();
        assertEquals(0.2, chosen.getTemperature());
        assertTrue(chosen.getTools().stream().noneMatch(t -> "models".equals(t.getName())));
        assertFalse(chosen.getSystemPrompt().contains("Use models"));
        SessionRecord another = service.createProjectSession(p.id, new CreateSessionRequest());
        Inbox defaults =
                service.submitProject(
                        p.id,
                        c.sessionId,
                        another.getSessionId(),
                        "default-model-probe",
                        null,
                        null,
                        true,
                        null);
        assertNull(defaults.projectModelId);
        await(
                () ->
                        model.requests.stream()
                                .anyMatch(
                                        request ->
                                                "test".equals(request.getModel())
                                                        && request.getMessages().stream()
                                                                .anyMatch(
                                                                        m ->
                                                                                m.getContent()
                                                                                                != null
                                                                                        && m.getContent()
                                                                                                .contains(
                                                                                                        "default-model-probe"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.submitProject(
                                p.id,
                                c.sessionId,
                                another.getSessionId(),
                                "bad model",
                                null,
                                null,
                                true,
                                "missing-model"));
    }

    @Test
    void delegationReturnsToOriginKeepsPrivacyAndRejectsCycles() throws Exception {
        Profile owner = profile("Coordinator"),
                peer = profile("Mac specialist"),
                outsider = profile("Other");
        owner.enabled = false;
        service.save(owner);
        Conversation origin = service.newConversation(owner.id, "Feature"),
                unrelated = service.newConversation(peer.id, "Private");
        AgentMessage secret = AgentMessage.user(unrelated.sessionId, "private fact");
        secret.setRunId("private");
        secret.setTurnId("turn-private");
        history.appendMessage(secret);
        Card task = new Card();
        task.title = "Verify feature";
        task.acceptance = "Verified result";
        task.conversationId = origin.sessionId;
        service.saveCard(task, owner.id);
        ReplyTarget feishu = new ReplyTarget("feishu", "account", "chat");
        Map<String, Object> receipt =
                service.sendToAgent(
                        owner.id,
                        origin.sessionId,
                        peer.id,
                        null,
                        "Check the requested feature",
                        task.id,
                        feishu);
        String delegated = (String) receipt.get("sessionId"),
                message = (String) receipt.get("messageId");
        assertEquals("ACCEPTED", receipt.get("status"));
        assertEquals("WAITING", store.card(task.id).status);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.sendToAgent(peer.id, delegated, owner.id, null, "Cycle", null, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.inspectDelegation(outsider.id, peer.id, delegated));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.inspectDelegation(owner.id, peer.id, unrelated.sessionId));
        await(() -> store.message("reply_" + message) != null);
        Inbox returned = store.message("reply_" + message);
        assertEquals("AGENT_REPLY", returned.source);
        assertEquals(origin.sessionId, returned.sessionId);
        assertEquals("feishu", returned.replyTo.channel);
        assertEquals("chat", returned.replyTo.recipientId);
        assertEquals(task.id, returned.taskId);
        service.deliverAgentReplies();
        assertEquals(
                1,
                store.messages(origin.sessionId).stream()
                        .filter(m -> m.id.equals(returned.id))
                        .count());
        Map<String, Object> inspection = service.inspectDelegation(owner.id, peer.id, delegated);
        assertFalse(json.writeValueAsString(inspection).contains("private fact"));
        List<AgentMessage> replies = (List<AgentMessage>) inspection.get("replies");
        assertFalse(replies.isEmpty());
        AgentMessage inbound =
                history.findMessages(delegated).stream()
                        .filter(
                                m ->
                                        m.getMetadataJson() != null
                                                && m.getMetadataJson().contains("agent_message"))
                        .findFirst()
                        .orElseThrow(AssertionError::new);
        assertTrue(inbound.getMetadataJson().contains("Coordinator"));
        assertEquals("user", inbound.getRole());
        assertEquals("AGENT", json.readTree(inbound.getMetadataJson()).path("source").asText());
        Card done = store.card(task.id);
        done.status = "DONE";
        Evidence evidence = new Evidence();
        evidence.sessionId = delegated;
        evidence.messageId = replies.get(replies.size() - 1).getMessageId();
        evidence.note = "Inspected the returned verification";
        done.evidence.add(evidence);
        service.saveCard(done, owner.id);
        assertEquals("DONE", store.card(task.id).status);
    }

    @Test
    void projectsOpenMatchesDirectoryAndAgentRunnerCannotChange() {
        Profile owner = profile("Project routing");
        CreateSessionRequest one = new CreateSessionRequest();
        one.setWorkspacePath("/repo/one");
        SessionRecord first = service.openProjectSession(owner.id, one, false);
        CreateSessionRequest two = new CreateSessionRequest();
        two.setWorkspacePath("/repo/two");
        SessionRecord second = service.openProjectSession(owner.id, two, false);
        assertNotEquals(first.getProjectId(), second.getProjectId());
        CreateSessionRequest back = new CreateSessionRequest();
        back.setWorkspacePath("/repo/one");
        assertEquals(
                first.getSessionId(),
                service.openProjectSession(owner.id, back, false).getSessionId());
        CreateSessionRequest mismatch = new CreateSessionRequest();
        mismatch.setProjectId(first.getProjectId());
        mismatch.setWorkspacePath("/repo/two");
        assertThrows(
                IllegalArgumentException.class,
                () -> service.openProjectSession(owner.id, mismatch, false));
        assertThrows(IllegalArgumentException.class, () -> service.save(new Profile()));
        owner.runnerId = "fixed-runner";
        store.save(owner);
        Profile changed = service.require(owner.id);
        changed.runnerId = "another-runner";
        assertThrows(IllegalArgumentException.class, () -> service.save(changed));
    }

    @Test
    void
            internalAgentCanReuseItsRunnerOrBindANewlyRegisteredMachineWithoutSharingProjectDirectories()
                    throws Exception {
        RunnerInfo local = new RunnerInfo();
        local.runnerId = "test_" + UUID.randomUUID().toString().replace("-", "");
        local.name = "Controller terminal";
        local.workspaces = true;
        local.workspaceRoot = "/test/agent-workspaces";
        ToolDescriptor read = new ToolDescriptor();
        read.name = "read";
        read.parameters.put("type", "object");
        local.tools.add(read);
        runners.register(local, "test-secret-that-is-long-enough-for-registration");
        Profile coordinator = new Profile();
        coordinator.name = "Coordinator";
        coordinator.runnerId = local.runnerId;
        service.save(coordinator);
        Conversation conversation = service.newConversation(coordinator.id, "Prepare environment");
        ToolContext context =
                new ToolContext(
                        conversation.sessionId,
                        "provision",
                        "turn",
                        null,
                        Collections.singletonMap("personalAgentId", coordinator.id));
        ToolDefinition agents = tools.get("agents", context);
        JsonNode discovered =
                json.readTree(
                        agents.execute(context, json.createObjectNode().put("action", "discover"))
                                .getContent());
        assertEquals(local.runnerId, discovered.path("currentRunnerId").asText());
        assertTrue(
                discovered
                        .path("runners")
                        .findValuesAsText("workspaceRoot")
                        .contains(local.workspaceRoot));
        JsonNode created =
                json.readTree(
                        agents.execute(
                                        context,
                                        json.createObjectNode()
                                                .put("action", "create")
                                                .put("name", "Developer")
                                                .put(
                                                        "description",
                                                        "Separate role on the same terminal"))
                                .getContent());
        String colleague = created.path("agentId").asText();
        assertNotEquals(coordinator.id, colleague);
        assertEquals(local.runnerId, created.path("runnerId").asText());
        SessionRecord ownProject =
                service.createProjectSession(coordinator.id, new CreateSessionRequest());
        SessionRecord colleagueProject =
                service.createProjectSession(colleague, new CreateSessionRequest());
        assertNotEquals(ownProject.getWorkspacePath(), colleagueProject.getWorkspacePath());
        assertTrue(
                colleagueProject
                        .getWorkspacePath()
                        .startsWith(local.workspaceRoot + "/agents/" + colleague + "/"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        agents.execute(
                                context,
                                json.createObjectNode()
                                        .put("action", "create")
                                        .put("name", "Remote builder")
                                        .put("description", "Not registered yet")
                                        .put("runnerId", "deploy@example.test")));
        // Registration happens after deployment by the process on the target machine.
        RunnerInfo remote = new RunnerInfo();
        remote.runnerId = "test_" + UUID.randomUUID().toString().replace("-", "");
        remote.name = "SSH build machine";
        remote.description = "Linux build environment";
        remote.platform = "Linux";
        remote.tools.add(read);
        runners.register(remote, "another-test-secret-long-enough-for-registration");
        discovered =
                json.readTree(
                        agents.execute(
                                        context,
                                        json.createObjectNode()
                                                .put("action", "discover")
                                                .put("query", "SSH build machine"))
                                .getContent());
        assertEquals(local.runnerId, discovered.path("currentRunnerId").asText());
        assertEquals(remote.runnerId, discovered.path("runners").get(0).path("runnerId").asText());
        assertTrue(discovered.path("runners").get(0).path("online").asBoolean());
        JsonNode remoteAgent =
                json.readTree(
                        agents.execute(
                                        context,
                                        json.createObjectNode()
                                                .put("action", "create")
                                                .put("name", "Remote builder")
                                                .put("description", "Build on Linux")
                                                .put("runnerId", remote.runnerId))
                                .getContent());
        assertEquals(
                remote.runnerId, service.require(remoteAgent.path("agentId").asText()).runnerId);
        assertEquals(local.runnerId, service.require(coordinator.id).runnerId);
    }

    @Test
    void externalAgentCanCreateThenSubmitReadProgressAndPageHistory() throws Exception {
        io.github.differentialmanifold.jagentharness.spring.web.TestHttpClient api =
                new io.github.differentialmanifold.jagentharness.spring.web.TestHttpClient(
                        "http://127.0.0.1:" + port, "test-token");
        RunnerInfo runner = new RunnerInfo();
        runner.runnerId = "test_" + UUID.randomUUID().toString().replace("-", "");
        runner.name = "External delegation terminal";
        ToolDescriptor tool = new ToolDescriptor();
        tool.name = "read";
        tool.parameters.put("type", "object");
        runner.tools.add(tool);
        runners.register(runner, "test-secret-that-is-long-enough-for-registration");
        JsonNode available = api.management("GET", "/api/v1/runners", null);
        assertTrue(available.findValuesAsText("runnerId").contains(runner.runnerId));
        Map<String, String> create = new LinkedHashMap<>();
        create.put("name", "External delegation");
        create.put("runnerId", runner.runnerId);
        create.put("instructions", "Verify results before reporting success.");
        Profile profile =
                json.treeToValue(
                        api.management("POST", "/api/v1/personal/agents", create), Profile.class);
        assertNotNull(profile.id);
        assertTrue(profile.enabled);
        assertEquals(runner.runnerId, service.require(profile.id).runnerId);
        assertEquals(create.get("instructions"), service.require(profile.id).instructions);
        Conversation chat =
                json.treeToValue(
                        api.management(
                                "POST",
                                "/api/v1/personal/agents/" + profile.id + "/conversations",
                                Collections.singletonMap("title", "API conversation")),
                        Conversation.class);
        String endpoint =
                "/api/v1/personal/agents/" + profile.id + "/conversations/" + chat.sessionId;
        Map<String, String> body = new LinkedHashMap<>();
        body.put("text", "Hello");
        body.put("messageId", "external_" + UUID.randomUUID());
        JsonNode receipt = api.management("POST", endpoint, body);
        assertEquals(body.get("messageId"), receipt.path("id").asText());
        await(() -> "DONE".equals(store.message(body.get("messageId")).status));
        Card task = new Card();
        task.title = "Work still outstanding";
        task.conversationId = chat.sessionId;
        task.status = "WAITING";
        service.saveCard(task, profile.id);
        JsonNode state =
                api.management(
                        "GET", endpoint + "/status?messageId=" + body.get("messageId"), null);
        assertEquals("DONE", state.path("message").path("status").asText());
        assertEquals(1, state.path("openTasks").asInt());
        JsonNode first = api.management("GET", endpoint + "/history?limit=1", null);
        assertEquals(1, first.path("messages").size());
        assertTrue(first.path("hasMore").asBoolean());
        String cursor = first.path("nextAfter").asText();
        JsonNode next = api.management("GET", endpoint + "/history?after=" + cursor, null);
        assertFalse(next.path("messages").isEmpty());
        assertNotEquals(cursor, next.path("messages").get(0).path("messageId").asText());
        JsonNode empty =
                api.management(
                        "GET",
                        endpoint + "/history?after=" + next.path("nextAfter").asText(),
                        null);
        assertEquals(0, empty.path("messages").size());
        assertEquals(next.path("nextAfter"), empty.path("nextAfter"));
        Conversation other = service.newConversation(profile.id, "Another conversation");
        assertEquals(
                404,
                assertThrows(
                                io.github.differentialmanifold.jagentharness.client
                                        .AgentHttpException.class,
                                () ->
                                        api.management(
                                                "GET",
                                                endpoint.replace(chat.sessionId, other.sessionId)
                                                        + "/status?messageId="
                                                        + body.get("messageId"),
                                                null))
                        .status);
        assertEquals(
                400,
                assertThrows(
                                io.github.differentialmanifold.jagentharness.client
                                        .AgentHttpException.class,
                                () ->
                                        api.management(
                                                "GET", endpoint + "/history?after=missing", null))
                        .status);
    }

    Profile profile(String name) {
        Profile p = new Profile();
        p.name = name;
        p.memory = "Prefer reproducible evidence.";
        p.id = "agent_" + java.util.UUID.randomUUID().toString().replace("-", "");
        store.save(p);
        return p;
    }

    void await(BooleanSupplier condition) throws Exception {
        long end = System.currentTimeMillis() + 8000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(25);
        assertTrue(condition.getAsBoolean());
    }

    @Test
    void bundledRunnerSkillIsDiscoveredWithoutInjectingItsProcedureAndLoadsOnDemand()
            throws Exception {
        Profile p = profile("Optional workflows");
        Conversation c = service.newConversation(p.id, "Setup");
        SessionRecord project = service.createProjectSession(p.id, new CreateSessionRequest());
        String path = "skills/runner-setup/SKILL.md";
        io.github.differentialmanifold.jagentharness.spring.web.dto.AgentContextRequest request =
                new io.github.differentialmanifold.jagentharness.spring.web.dto
                        .AgentContextRequest();
        request.setSessionId(c.sessionId);
        String prompt = promptController.preview(request).getSystemPrompt();
        assertTrue(prompt.contains(path));
        assertFalse(prompt.contains("--home"));
        assertTrue(
                contextController.current(request).getSkills().stream()
                        .anyMatch(skill -> path.equals(skill.getFilePath())));
        ToolContext context =
                new ToolContext(
                        c.sessionId,
                        "run",
                        "turn",
                        null,
                        Collections.singletonMap("personalAgentId", p.id));
        JsonNode args = json.createObjectNode().put("path", path);
        JsonNode loaded =
                json.readTree(tools.get("skill", context).execute(context, args).getContent());
        assertEquals(path, loaded.path("path").asText());
        assertTrue(loaded.path("content").asText().contains("--home"));
        assertNull(files.readFile(KnowledgeScope.agent(p.id), path));
        assertNull(files.readFile(KnowledgeScope.agentGlobal(), path));
        // Reuse the existing activation receipts, without putting the full procedure in the prompt.
        String after = promptController.preview(request).getSystemPrompt();
        assertTrue(after.contains("Skills previously loaded"));
        assertFalse(after.contains("--home"));
        request.setSessionId(project.getSessionId());
        assertFalse(promptController.preview(request).getSystemPrompt().contains(path));
        ToolContext projectContext =
                new ToolContext(
                        project.getSessionId(),
                        "run",
                        "turn",
                        null,
                        Collections.singletonMap("personalAgentId", p.id));
        assertThrows(
                IllegalArgumentException.class,
                () -> tools.get("skill", projectContext).execute(projectContext, args));
        // A private method may intentionally override a bundled default; no database seeding.
        files.writeFile(
                KnowledgeScope.agent(p.id),
                path,
                "---\nname: runner-setup\ndescription: Custom setup\n---\nUse our managed fleet",
                "text/markdown");
        assertTrue(
                tools.get("skill", context)
                        .execute(context, args)
                        .getContent()
                        .contains("managed fleet"));
        request.setSessionId(c.sessionId);
        assertEquals(
                1,
                contextController.current(request).getSkills().stream()
                        .filter(skill -> "runner-setup".equals(skill.getName()))
                        .count());
        assertTrue(promptController.preview(request).getSystemPrompt().contains("Custom setup"));
    }

    @Test
    void projectAndAgentDefaultsStaySeparateInPromptCatalogAndSkillReads() throws Exception {
        Profile p = profile("Independent defaults");
        Conversation c = service.newConversation(p.id, "Agent defaults");
        SessionRecord project = service.createProjectSession(p.id, new CreateSessionRequest());
        String path = "skills/default-check/SKILL.md";
        files.writeFile(
                KnowledgeScope.global(), "AGENTS.md", "PROJECT_DEFAULT_SENTINEL", "text/markdown");
        files.writeFile(
                KnowledgeScope.agentGlobal(),
                "AGENTS.md",
                "AGENT_DEFAULT_SENTINEL",
                "text/markdown");
        files.writeFile(
                KnowledgeScope.global(),
                path,
                "---\nname: default-check\ndescription: Project default method\n---\nProject-only method",
                "text/markdown");
        files.writeFile(
                KnowledgeScope.agentGlobal(),
                path,
                "---\nname: default-check\ndescription: Agent default method\n---\nAgent-only method",
                "text/markdown");
        try {
            io.github.differentialmanifold.jagentharness.spring.web.dto.AgentContextRequest
                    request =
                            new io.github.differentialmanifold.jagentharness.spring.web.dto
                                    .AgentContextRequest();
            request.setSessionId(c.sessionId);
            String agentPrompt = promptController.preview(request).getSystemPrompt();
            assertTrue(agentPrompt.contains("AGENT_DEFAULT_SENTINEL"));
            assertFalse(agentPrompt.contains("PROJECT_DEFAULT_SENTINEL"));
            assertTrue(
                    contextController.current(request).getSkills().stream()
                            .anyMatch(
                                    t ->
                                            "default-check".equals(t.getName())
                                                    && "Agent default method"
                                                            .equals(t.getDescription())));
            ToolContext agentContext =
                    new ToolContext(
                            c.sessionId,
                            "run",
                            "turn",
                            null,
                            Collections.singletonMap("personalAgentId", p.id));
            assertTrue(
                    tools.get("skill", agentContext)
                            .execute(agentContext, json.readTree("{\"path\":\"" + path + "\"}"))
                            .getContent()
                            .contains("Agent-only method"));
            request.setSessionId(project.getSessionId());
            String projectPrompt = promptController.preview(request).getSystemPrompt();
            assertTrue(projectPrompt.contains("PROJECT_DEFAULT_SENTINEL"));
            assertFalse(projectPrompt.contains("AGENT_DEFAULT_SENTINEL"));
            assertTrue(
                    contextController.current(request).getSkills().stream()
                            .anyMatch(
                                    t ->
                                            "default-check".equals(t.getName())
                                                    && "Project default method"
                                                            .equals(t.getDescription())));
            files.deleteFile(KnowledgeScope.agentGlobal(), path);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            tools.get("skill", agentContext)
                                    .execute(
                                            agentContext,
                                            json.readTree("{\"path\":\"" + path + "\"}")));
            files.writeFile(
                    KnowledgeScope.agentGlobal(),
                    path,
                    "---\nname: default-check\ndescription: Shared method\n---\nAgent shared method",
                    "text/markdown");
            files.writeFile(
                    KnowledgeScope.agent(p.id),
                    path,
                    "---\nname: default-check\ndescription: Private method\n---\nPrivate method",
                    "text/markdown");
            assertTrue(
                    tools.get("skill", agentContext)
                            .execute(agentContext, json.readTree("{\"path\":\"" + path + "\"}"))
                            .getContent()
                            .contains("Private method"));
        } finally {
            for (KnowledgeScope scope :
                    Arrays.asList(KnowledgeScope.global(), KnowledgeScope.agentGlobal())) {
                files.deleteFile(scope, "AGENTS.md");
                files.deleteFile(scope, path);
            }
        }
    }

    @Test
    void contextAndPreviewUseTheActualAgentIdentityAndToolSelection() throws Exception {
        Profile p = profile("Context owner");
        p.instructions = "Keep progress verifiable.";
        p.disabledTools = Collections.singletonList("projects_create");
        p = service.save(p);
        Conversation c = service.newConversation(p.id, "Context");
        io.github.differentialmanifold.jagentharness.spring.web.dto.AgentContextRequest request =
                new io.github.differentialmanifold.jagentharness.spring.web.dto
                        .AgentContextRequest();
        request.setSessionId(c.sessionId);
        request.setClientInstructions("Do not inject a Runner into personal chat");
        io.github.differentialmanifold.jagentharness.spring.web.dto.AgentContextResponse result =
                contextController.current(request);
        assertTrue(result.getTools().stream().anyMatch(t -> "projects".equals(t.getName())));
        assertFalse(
                result.getTools().stream().anyMatch(t -> "projects_create".equals(t.getName())));
        assertTrue(
                result.getTools().stream().allMatch(t -> "server".equals(t.getExecutionTarget())));
        assertFalse(
                result.getSkills().stream()
                        .anyMatch(t -> "personal-assistant".equals(t.getName())));
        String prompt = promptController.preview(request).getSystemPrompt();
        assertTrue(prompt.contains("Context owner"));
        assertTrue(prompt.contains("Keep progress verifiable."));
        assertFalse(prompt.contains(p.memory));
        assertFalse(prompt.contains("Do not inject a Runner"));
        ToolContext toolContext =
                new ToolContext(
                        c.sessionId,
                        "run",
                        "turn",
                        null,
                        Collections.singletonMap("personalAgentId", p.id));
        ToolDefinition grouped = tools.get("projects", toolContext);
        assertNotNull(grouped);
        assertFalse(
                grouped.getParametersSchema()
                        .path("properties")
                        .path("action")
                        .path("enum")
                        .toString()
                        .contains("open"));
        assertThrows(
                IllegalArgumentException.class,
                () -> grouped.execute(toolContext, json.createObjectNode().put("action", "open")));
        SessionRecord project = service.createProjectSession(p.id, new CreateSessionRequest());
        request.setSessionId(project.getSessionId());
        assertFalse(
                contextController.current(request).getTools().stream()
                        .anyMatch(t -> "projects".equals(t.getName())));
    }

    @Test
    void deletingConversationDetachesWorkAndKeepsProjectsAndKnowledge() throws Exception {
        Profile p = profile("Lifecycle");
        p.enabled = false;
        p = service.save(p);
        files.writeFile(
                KnowledgeScope.agent(p.id),
                "skills/lifecycle/SKILL.md",
                "---\nname: lifecycle\ndescription: Retained method\n---\nA reusable method.",
                "text/markdown");
        Conversation c = service.newConversation(p.id, "Original");
        SessionRecord project = service.createProjectSession(p.id, new CreateSessionRequest());
        service.renameConversation(p.id, c.sessionId, "Renamed");
        assertEquals("Renamed", store.conversation(c.sessionId).title);
        assertEquals("Renamed", sessions.requireSession(c.sessionId).getTitle());
        Profile other = profile("Different owner");
        assertThrows(
                PersonalAgentException.class,
                () -> service.deleteConversation(other.id, c.sessionId));
        Card task = new Card();
        task.title = "Follow progress";
        task.conversationId = c.sessionId;
        task.sessionId = project.getSessionId();
        task.nextCheckAt = System.currentTimeMillis() + 120000;
        task = service.saveCard(task, p.id);
        Inbox pending = new Inbox();
        pending.id = "pending-" + UUID.randomUUID();
        pending.agentId = p.id;
        pending.sessionId = c.sessionId;
        pending.source = "USER";
        pending.text = "Later";
        store.enqueue(pending);
        store.channel(p.id, "{\"sessionId\":\"" + c.sessionId + "\",\"appSecret\":\"preserved\"}");
        service.deleteConversation(p.id, c.sessionId);
        assertNull(store.conversation(c.sessionId));
        assertTrue(store.messages(c.sessionId).isEmpty());
        assertThrows(SessionNotFoundException.class, () -> sessions.requireSession(c.sessionId));
        assertNull(store.card(task.id).conversationId);
        assertEquals(0, store.card(task.id).nextCheckAt);
        assertEquals("WAITING", store.card(task.id).status);
        assertNotNull(sessions.requireSession(project.getSessionId()));
        assertEquals(p.memory, store.profile(p.id).memory);
        assertNotNull(files.readFile(KnowledgeScope.agent(p.id), "skills/lifecycle/SKILL.md"));
        assertTrue(json.readTree(store.channel(p.id)).path("sessionId").isNull());
        assertEquals("preserved", json.readTree(store.channel(p.id)).path("appSecret").asText());
        pending.status = "PENDING";
        assertFalse(
                store.update(
                        pending,
                        "WAITING")); // A late project callback cannot recreate deleted work.
    }

    @Test
    void runningConversationCannotBeDeleted() {
        Profile p = profile("Busy lifecycle");
        Conversation c = service.newConversation(p.id, "Busy");
        synchronized (service) {
            Inbox running = new Inbox();
            running.id = "busy-" + UUID.randomUUID();
            running.agentId = p.id;
            running.sessionId = c.sessionId;
            running.source = "USER";
            running.status = "RUNNING";
            store.enqueue(running);
            PersonalAgentException error =
                    assertThrows(
                            PersonalAgentException.class,
                            () -> service.deleteConversation(p.id, c.sessionId));
            assertEquals(PersonalAgentException.Kind.CONFLICT, error.kind);
            assertNotNull(store.conversation(c.sessionId));
            running.status = "DONE";
            store.update(running, "RUNNING");
        }
    }

    @Test
    void conversationsReuseHistoryWithoutBecomingProjectsAndToolsStayScoped() throws Exception {
        Profile p = profile("Researcher");
        int projects = db.queryForObject("select count(*) from projects", Integer.class);
        Conversation c = service.newConversation(p.id, "A question");
        assertTrue(records.findBySessionId(c.sessionId).isStandalone());
        assertEquals(projects, db.queryForObject("select count(*) from projects", Integer.class));
        assertFalse(
                sessions.listSessions().stream()
                        .anyMatch(s -> c.sessionId.equals(s.getSessionId())));
        assertNull(tools.get("projects", new AgentContext(c.sessionId, "run", "turn")));
        ToolContext context =
                new ToolContext(
                        c.sessionId,
                        "run",
                        "turn",
                        null,
                        Collections.singletonMap("personalAgentId", p.id));
        assertNotNull(tools.get("projects", context));
        assertNull(tools.get("console", context));
        assertTrue(
                tools.get("projects", context)
                        .getParametersSchema()
                        .path("properties")
                        .has("sessionId"));
        assertNull(
                files.readFile(KnowledgeScope.agent(p.id), "skills/personal-assistant/SKILL.md"));
        Inbox accepted = service.send(p.id, c.sessionId, "hello", "input-" + UUID.randomUUID());
        assertEquals(accepted.id, service.send(p.id, c.sessionId, "hello", accepted.id).id);
        await(() -> "DONE".equals(store.message(accepted.id).status));
        assertTrue(store.message(accepted.id).answer.contains("Reviewed"));
        assertTrue(
                model.requests.stream()
                        .anyMatch(
                                req ->
                                        req.getMessages().stream()
                                                .anyMatch(
                                                        m ->
                                                                AgentMessage.isRuntimeContext(m)
                                                                        && m.getContent()
                                                                                .contains(
                                                                                        "Prefer reproducible evidence."))));
        assertFalse(history.findMessages(c.sessionId).isEmpty());
        Profile other = profile("Other");
        assertThrows(PersonalAgentException.class, () -> service.history(other.id, c.sessionId));
    }

    @Test
    void ordinaryProjectCompletesAndWakesAssistantWithVisibleAuthorship() throws Exception {
        Profile p = profile("Project operator");
        Conversation c = service.newConversation(p.id, "Goal");
        CreateSessionRequest create = new CreateSessionRequest();
        create.setProjectName("Research run");
        create.setTitle("Experiment");
        SessionRecord project = service.createProjectSession(p.id, create);
        Inbox watch =
                service.submitProject(
                        p.id, c.sessionId, project.getSessionId(), "local project work");
        await(() -> "DONE".equals(store.message(watch.id).status));
        assertTrue(
                sessions.listSessions().stream()
                        .anyMatch(s -> s.getSessionId().equals(project.getSessionId())));
        assertTrue(
                history.findMessages(project.getSessionId()).stream()
                        .anyMatch(
                                m ->
                                        m.getContent() != null
                                                && m.getContent()
                                                        .contains(
                                                                "From personal Agent: Project operator")));
        assertTrue(store.message(watch.id).text.contains("Project evidence verified"));
        assertTrue(
                model.requests.stream()
                        .filter(
                                req ->
                                        req.getMessages()
                                                .get(0)
                                                .getSessionId()
                                                .equals(project.getSessionId()))
                        .allMatch(
                                req ->
                                        req.getTools().stream()
                                                .noneMatch(t -> "projects".equals(t.getName()))));
    }

    @Test
    void remoteToolsStillUseOrdinaryRunnerConversationProtocol() throws Exception {
        Profile p = profile("Remote operator");
        Conversation c = service.newConversation(p.id, "Remote goal");
        RunnerInfo runner = new RunnerInfo();
        runner.runnerId = "test_" + UUID.randomUUID().toString().replace("-", "");
        runner.name = "remote";
        runner.instructions = "Use local tools";
        runner.workspaces = false;
        ToolDescriptor tool = new ToolDescriptor();
        tool.name = "remote_read";
        tool.parameters.put("type", "object");
        runner.tools.add(tool);
        runners.register(runner, "test-secret-that-is-long-enough-for-registration");
        CreateSessionRequest create = new CreateSessionRequest();
        p.runnerId = runner.runnerId;
        service.save(p);
        create.setProjectName("Remote project");
        SessionRecord project = service.createProjectSession(p.id, create);
        Inbox watch =
                service.submitProject(
                        p.id, c.sessionId, project.getSessionId(), "remote project work");
        RunnerPoll poll = (RunnerPoll) runners.poll(runner.runnerId, null).getNow(null);
        assertNotNull(poll.job);
        ChatResponse pending = chat.chat(poll.job.request, null);
        assertEquals("REQUIRES_ACTION", pending.status);
        assertEquals("remote_read", pending.toolCalls.get(0).name);
        ChatRequest result = new ChatRequest();
        result.sessionId = project.getSessionId();
        result.runId = pending.runId;
        result.messages.add(
                ChatMessage.tool(
                        pending.toolCalls.get(0).toolCallId, "SUCCEEDED", "native evidence"));
        result.clientTools = poll.job.request.clientTools;
        ChatResponse finalResult = chat.chat(result, null);
        runners.complete(runner.runnerId, finalResult);
        await(() -> "DONE".equals(store.message(watch.id).status));
        assertTrue(store.message(watch.id).text.contains("Project evidence verified"));
    }

    @Test
    void privateTaskRejectsLostUpdatesAndScheduledReviewIsOneShot() throws Exception {
        Profile owner = profile("Owner");
        Conversation c = service.newConversation(owner.id, "Plan");
        Card card = new Card();
        card.title = "Check experiment";
        card.ownerId = owner.id;
        card.conversationId = c.sessionId;
        card.nextCheckAt = System.currentTimeMillis() + 120000;
        service.saveCard(card, owner.id);
        Card stale = store.card(card.id);
        Card fresh = store.card(card.id);
        fresh.progress = "Evidence received";
        service.saveCard(fresh, owner.id);
        stale.progress = "Overwrite";
        assertThrows(PersonalAgentException.class, () -> service.saveCard(stale, owner.id));
        assertEquals("Evidence received", store.card(card.id).progress);
        fresh = store.card(card.id);
        fresh.nextCheckAt = System.currentTimeMillis() - 1;
        assertTrue(store.save(fresh));
        service.tick();
        service.tick();
        await(
                () ->
                        store.messages(c.sessionId).stream()
                                .anyMatch(
                                        m -> "CHECK".equals(m.source) && "DONE".equals(m.status)));
        assertEquals(
                1,
                store.messages(c.sessionId).stream().filter(m -> "CHECK".equals(m.source)).count());
        assertEquals(0, store.card(card.id).nextCheckAt);
    }

    @Test
    void pauseRetainsQueuedWorkWithoutStartingAnotherAgent() throws Exception {
        Profile p = profile("Paused worker");
        Conversation c = service.newConversation(p.id, "Plan");
        p.enabled = false;
        service.save(p);
        Inbox pending = new Inbox();
        pending.id = "queued-" + UUID.randomUUID();
        pending.agentId = p.id;
        pending.sessionId = c.sessionId;
        pending.source = "USER";
        pending.text = "Continue work";
        store.enqueue(pending);
        service.tick();
        assertEquals("PENDING", store.message(pending.id).status);
        p.enabled = true;
        service.save(p);
        await(() -> "DONE".equals(store.message(pending.id).status));
    }

    @Test
    void projectsAndTasksAreIsolatedByTheirCreator() throws Exception {
        Profile first = profile("Developer A"), second = profile("Developer B");
        Conversation a = service.newConversation(first.id, "Build"),
                b = service.newConversation(second.id, "Build");
        SessionRecord userProject = sessions.createSession("User work", null, "Same name");
        CreateSessionRequest create = new CreateSessionRequest();
        create.setProjectName("Same name");
        SessionRecord owned = service.createProjectSession(first.id, create);
        SessionRecord other = service.createProjectSession(second.id, new CreateSessionRequest());
        assertEquals(first.id, records.findBySessionId(owned.getSessionId()).getOwnerAgentId());
        assertTrue(
                sessionController.list().stream()
                        .anyMatch(s -> s.getSessionId().equals(userProject.getSessionId())));
        assertTrue(sessionController.list().stream().allMatch(s -> s.getOwnerAgentId() == null));
        assertEquals(1, sessionController.listForOwner(first.id).size());
        assertEquals(1, service.projectSessions(first.id).size());
        assertThrows(
                PersonalAgentException.class,
                () -> service.inspectProject(second.id, owned.getSessionId(), null));
        assertThrows(
                PersonalAgentException.class,
                () ->
                        service.submitProject(
                                first.id,
                                a.sessionId,
                                userProject.getSessionId(),
                                "do not execute"));
        assertThrows(
                PersonalAgentException.class,
                () ->
                        service.submitProject(
                                first.id, a.sessionId, other.getSessionId(), "do not execute"));
        Inbox legacyWatch = new Inbox();
        legacyWatch.id = "old-watch-" + UUID.randomUUID();
        legacyWatch.agentId = first.id;
        legacyWatch.sessionId = a.sessionId;
        legacyWatch.source = "PROJECT";
        legacyWatch.status = "WAITING";
        legacyWatch.projectSessionId = other.getSessionId();
        store.enqueue(legacyWatch);
        service.tick();
        assertEquals("FAILED", store.message(legacyWatch.id).status);
        create.setProjectId(owned.getProjectId());
        SessionRecord followup = service.createProjectSession(first.id, create);
        assertEquals(first.id, followup.getOwnerAgentId());
        assertEquals(owned.getProjectId(), followup.getProjectId());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.createProjectSession(second.id, create));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        sessionController.create(
                                json.convertValue(create, CreateSessionRequest.class)));
        Card task = new Card();
        task.title = "Follow up";
        task.sessionId = owned.getSessionId();
        service.saveCard(task, first.id);
        assertTrue(service.cards(second.id).isEmpty());
        assertThrows(PersonalAgentException.class, () -> service.saveCard(task, second.id));
        task.sessionId = other.getSessionId();
        assertThrows(PersonalAgentException.class, () -> service.saveCard(task, first.id));
        ToolContext context =
                new ToolContext(
                        a.sessionId,
                        "run",
                        "turn",
                        null,
                        Collections.singletonMap("personalAgentId", first.id));
        assertNull(tools.get("agents_send", context));
        assertNull(tools.get("cards_record", context));
        assertThrows(
                PersonalAgentException.class,
                () ->
                        tools.get("projects", context)
                                .execute(
                                        context,
                                        json.createObjectNode()
                                                .put("action", "inspect")
                                                .put("sessionId", other.getSessionId())));
    }

    @Test
    void legacyOwnershipUsesCreationEvidenceRatherThanProjectUse() throws Exception {
        Profile p = profile("Original creator");
        Conversation c = service.newConversation(p.id, "Old work");
        SessionRecord created = sessions.createSession("Created", null, "Legacy project");
        SessionRecord reused = sessions.createSession("User session", null, "User project");
        for (SessionRecord project : Arrays.asList(created, reused)) {
            String callId = "create-" + project.getSessionId();
            com.fasterxml.jackson.databind.node.ObjectNode args =
                    json.createObjectNode().put("action", "projects.create");
            com.fasterxml.jackson.databind.node.ObjectNode body = args.putObject("body");
            if (project == reused) body.put("projectId", reused.getProjectId());
            AgentMessage call =
                    AgentMessage.assistant(
                            c.sessionId,
                            "",
                            Collections.singletonList(
                                    new ToolCall(callId, "console", args.toString())));
            call.setRunId("legacy-run");
            call.setTurnId("legacy-turn");
            history.appendMessage(call);
            AgentMessage result =
                    AgentMessage.tool(
                            c.sessionId, callId, "console", json.writeValueAsString(project));
            result.setRunId("legacy-run");
            result.setTurnId("legacy-turn");
            history.appendMessage(result);
        }
        for (int i = 0; i < 2; i++) {
            try (PersonalAgentService restarted =
                    new PersonalAgentService(
                            store, records, sessions, history, harness, projects, runs, knowledge,
                            learning)) {
                restarted.start();
            }
        }
        assertEquals(p.id, records.findBySessionId(created.getSessionId()).getOwnerAgentId());
        assertNull(records.findBySessionId(reused.getSessionId()).getOwnerAgentId());
    }

    @Test
    void aProblemStartsAProjectAndItsResultWakesTheAgent() throws Exception {
        Profile p = profile("Independent developer");
        Conversation c = service.newConversation(p.id, "Solve a problem");
        Inbox request =
                service.send(
                        p.id, c.sessionId, "I need a release checklist for my application", null);
        await(
                () ->
                        !Arrays.asList("PENDING", "RUNNING")
                                .contains(store.message(request.id).status));
        assertEquals("DONE", store.message(request.id).status, store.message(request.id).error);
        assertFalse(
                service.projectSessions(p.id).isEmpty(),
                json.writeValueAsString(history.findMessages(c.sessionId)));
        assertTrue(
                store.messages(c.sessionId).stream().anyMatch(m -> "PROJECT".equals(m.source)),
                json.writeValueAsString(history.findMessages(c.sessionId)));
        await(
                () ->
                        store.messages(c.sessionId).stream()
                                .anyMatch(
                                        m ->
                                                "PROJECT".equals(m.source)
                                                        && "DONE".equals(m.status)));
        assertEquals("DONE", store.message(request.id).status);
        List<SessionRecord> projects = service.projectSessions(p.id);
        assertEquals(1, projects.size());
        assertTrue(
                history.findMessages(projects.get(0).getSessionId()).stream()
                        .anyMatch(
                                m ->
                                        "assistant".equals(m.getRole())
                                                && m.getContent()
                                                        .contains("Project evidence verified")));
    }

    @Test
    void defaultDirectoriesSeparateUsersAgentsAndProjects() throws Exception {
        RunnerInfo runner = new RunnerInfo();
        runner.runnerId = "test_" + UUID.randomUUID().toString().replace("-", "");
        runner.name = "Directories";
        runner.workspaces = true;
        runner.workspaceRoot = "C:\\runner\\workspaces";
        ToolDescriptor tool = new ToolDescriptor();
        tool.name = "read";
        tool.parameters.put("type", "object");
        runner.tools.add(tool);
        runners.register(runner, "test-secret-that-is-long-enough-for-registration");
        Profile agent = profile("Default workspace");
        agent.runnerId = runner.runnerId;
        service.save(agent);
        SessionRecord first = service.createProjectSession(agent.id, new CreateSessionRequest());
        SessionRecord second = service.createProjectSession(agent.id, new CreateSessionRequest());
        assertNotEquals(first.getWorkspacePath(), second.getWorkspacePath());
        assertTrue(first.getWorkspacePath().contains("/agents/" + agent.id + "/project_"));
        CreateSessionRequest user = new CreateSessionRequest();
        user.setRunnerId(runner.runnerId);
        SessionRecord userSession = runners.createSession(user, null);
        assertTrue(userSession.getWorkspacePath().contains("/user/project_"));
        CreateSessionRequest explicit = new CreateSessionRequest();
        explicit.setWorkspacePath("/existing/repository");
        CompletableFuture<SessionRecord> opened =
                CompletableFuture.supplyAsync(
                        () -> service.createProjectSession(agent.id, explicit));
        RunnerPoll lookup = runners.poll(runner.runnerId, null).get(2, TimeUnit.SECONDS);
        WorkspaceListing listing = new WorkspaceListing();
        listing.requestId = lookup.workspaceRequest.requestId;
        listing.path = "/existing/repository";
        runners.workspaceResult(runner.runnerId, listing);
        assertEquals("/existing/repository", opened.get(2, TimeUnit.SECONDS).getWorkspacePath());
        CreateSessionRequest extra = new CreateSessionRequest();
        extra.setProjectId(first.getProjectId());
        assertEquals(
                first.getWorkspacePath(),
                service.createProjectSession(agent.id, extra).getWorkspacePath());
    }

    @Test
    void memoryToolsWorkWithoutSkillsAndRejectStaleEdits() throws Exception {
        Profile p = profile("Direct tools");
        Conversation c = service.newConversation(p.id, "Remember");
        assertTrue(files.listFiles(KnowledgeScope.agent(p.id), "skills").isEmpty());
        ToolContext context =
                new ToolContext(
                        c.sessionId,
                        "run",
                        "turn",
                        null,
                        Collections.singletonMap("personalAgentId", p.id));
        com.fasterxml.jackson.databind.JsonNode memory =
                json.readTree(
                        tools.get("memory", context)
                                .execute(context, json.createObjectNode().put("action", "read"))
                                .getContent());
        com.fasterxml.jackson.databind.node.ObjectNode args =
                json.createObjectNode()
                        .put("action", "add")
                        .put("revision", memory.path("revision").asLong())
                        .put("category", "preference")
                        .put("content", "Use simple examples.")
                        .put("reason", "User preference");
        tools.get("memory", context).execute(context, args);
        assertTrue(store.profile(p.id).memory.contains("Use simple examples."));
        assertThrows(
                PersonalAgentException.class,
                () -> tools.get("memory", context).execute(context, args));
    }

    @Test
    void restartDoesNotReplayAnUncertainModelTurn() throws Exception {
        Profile p = profile("Recoverable");
        Conversation c = service.newConversation(p.id, "Interrupted");
        Inbox m = new Inbox();
        m.id = "interrupted_" + UUID.randomUUID();
        m.agentId = p.id;
        m.sessionId = c.sessionId;
        m.source = "USER";
        m.text = "Do not repeat effects";
        m.status = "RUNNING";
        store.enqueue(m);
        AgentMessage unanswered =
                AgentMessage.assistant(
                        c.sessionId,
                        "",
                        Collections.singletonList(new ToolCall("uncertain-call", "console", "{}")));
        unanswered.setRunId("interrupted-run");
        unanswered.setTurnId("interrupted-turn");
        history.appendMessage(unanswered);
        PersonalAgentService restarted =
                new PersonalAgentService(
                        store, records, sessions, history, harness, projects, runs, knowledge,
                        learning);
        restarted.start();
        restarted.close();
        assertEquals("FAILED", store.message(m.id).status);
        assertTrue(store.message(m.id).error.contains("recovery check"));
        assertEquals(
                1,
                store.messages(c.sessionId).stream()
                        .filter(item -> "RECOVERY".equals(item.source))
                        .count());
        assertTrue(ConversationHistory.pendingTools(history.findMessages(c.sessionId)).isEmpty());
        assertTrue(
                history.findMessages(c.sessionId).stream()
                        .anyMatch(message -> message.getContent().contains("UNKNOWN")));
    }

    @Test
    void taskRoutesSurviveEditsAndChecksWhileWebWorkStaysLocal() throws Exception {
        Profile p = profile("Follow original channel");
        Conversation c = service.newConversation(p.id, "Original channel");
        ReplyTarget origin = new ReplyTarget("feishu", "app", "ou_origin");
        Card card = new Card();
        card.title = "quiet-review";
        card.conversationId = c.sessionId;
        card.nextCheckAt = System.currentTimeMillis() + 120000;
        Card saved = service.saveCard(card, p.id, origin);
        Card edited = store.card(saved.id);
        edited.replyTo = new ReplyTarget("feishu", "other-app", "someone-else");
        service.saveCard(edited, p.id);
        assertEquals("ou_origin", store.card(saved.id).replyTo.recipientId);
        // Advance only this fixture's due time; the scheduler still performs the persisted wakeup.
        Card due = store.card(saved.id);
        due.nextCheckAt = System.currentTimeMillis() - 1;
        assertTrue(store.save(due));
        service.tick();
        await(
                () ->
                        store.messages(c.sessionId).stream()
                                .anyMatch(
                                        m -> "CHECK".equals(m.source) && "DONE".equals(m.status)));
        Inbox check =
                store.messages(c.sessionId).stream()
                        .filter(m -> "CHECK".equals(m.source))
                        .findFirst()
                        .get();
        assertEquals("app", check.replyTo.accountId);
        assertEquals("", check.answer);
        assertTrue(check.delivered);
        assertEquals(0, store.card(saved.id).nextCheckAt);
        assertTrue(
                history.findMessages(c.sessionId).stream()
                        .noneMatch(
                                m ->
                                        m.getContent() != null
                                                && m.getContent().contains("userReply")));

        Card web = new Card();
        web.title = "Web task";
        web.conversationId = c.sessionId;
        web.replyTo = origin; // A UI request cannot supply its own notification destination.
        assertNull(service.saveCard(web, p.id).replyTo);
        SessionRecord project = service.createProjectSession(p.id, new CreateSessionRequest());
        Inbox watch =
                service.submitProject(p.id, c.sessionId, project.getSessionId(), "Verify", origin);
        await(() -> "DONE".equals(store.message(watch.id).status));
        assertEquals("ou_origin", store.message(watch.id).replyTo.recipientId);
        Inbox webWatch =
                service.submitProject(p.id, c.sessionId, project.getSessionId(), "Verify again");
        assertNull(store.message(webWatch.id).replyTo);
    }

    @Test
    void groupedToolsEnforceFieldsAndLivePermissions() throws Exception {
        Profile p = profile("Tool boundaries");
        Conversation c = service.newConversation(p.id, "Actions");
        ToolContext context =
                new ToolContext(
                        c.sessionId,
                        "run",
                        "turn",
                        null,
                        Collections.singletonMap("personalAgentId", p.id));
        for (String removed :
                Arrays.asList(
                        "runners_list",
                        "notify_user",
                        "conversations_list",
                        "conversations_read",
                        "console")) assertNull(tools.get(removed, context));
        ToolDefinition projects = tools.get("projects", context);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        projects.execute(
                                context,
                                json.createObjectNode()
                                        .put("action", "open")
                                        .put("runnerId", "different-runner")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        projects.execute(
                                context,
                                json.createObjectNode()
                                        .put("action", "send")
                                        .put("text", "Missing session")));
        p.disabledTools = Arrays.asList("projects_send", "memory_remove");
        service.save(p);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        projects.execute(
                                context,
                                json.createObjectNode()
                                        .put("action", "send")
                                        .put("sessionId", "ignored")
                                        .put("text", "blocked")));
        assertFalse(
                tools.get("memory", context)
                        .getParametersSchema()
                        .path("properties")
                        .path("action")
                        .path("enum")
                        .toString()
                        .contains("edit"));
        assertEquals(
                "[]",
                projects.execute(context, json.createObjectNode().put("action", "list"))
                        .getContent());
    }

    Card planned(Profile p, Conversation c, String title, String parent) {
        Card task = new Card();
        task.title = title;
        task.parentId = parent;
        task.conversationId = c.sessionId;
        task.acceptance = "Run a reproducible check and cite the project output";
        return service.saveCard(task, p.id);
    }

    Evidence evidence(String session) {
        AgentMessage output =
                history.findMessages(session).stream()
                        .filter(
                                m ->
                                        "assistant".equals(m.getRole())
                                                && m.getContent() != null
                                                && !m.getContent().isEmpty())
                        .reduce((a, b) -> b)
                        .get();
        Evidence evidence = new Evidence();
        evidence.sessionId = session;
        evidence.messageId = output.getMessageId();
        evidence.note = "Verified project output";
        return evidence;
    }

    @Test
    void taskGraphRejectsCyclesForeignReferencesAndPrematureAcceptance() throws Exception {
        Profile p = profile("Task graph");
        p.enabled = false;
        service.save(p);
        Conversation c = service.newConversation(p.id, "Plan");
        Card root = planned(p, c, "Root", null),
                a = planned(p, c, "Investigate", root.id),
                b = planned(p, c, "Implement", root.id);
        b.dependsOn.add(a.id);
        service.saveCard(b, p.id);
        Card cycle = store.card(a.id);
        cycle.dependsOn.add(b.id);
        assertThrows(IllegalArgumentException.class, () -> service.saveCard(cycle, p.id));
        Card hierarchyCycle = store.card(root.id);
        hierarchyCycle.parentId = b.id;
        assertThrows(IllegalArgumentException.class, () -> service.saveCard(hierarchyCycle, p.id));
        Profile other = profile("Other plan");
        Conversation oc = service.newConversation(other.id, "Other");
        Card foreign = planned(other, oc, "Foreign", null);
        Card cross = store.card(a.id);
        cross.dependsOn.add(foreign.id);
        assertThrows(IllegalArgumentException.class, () -> service.saveCard(cross, p.id));
        SessionRecord project = service.createProjectSession(p.id, new CreateSessionRequest());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.submitProject(
                                p.id,
                                c.sessionId,
                                project.getSessionId(),
                                "Too early",
                                null,
                                b.id,
                                false));
        Card noProof = store.card(a.id);
        noProof.status = "DONE";
        assertThrows(IllegalArgumentException.class, () -> service.saveCard(noProof, p.id));
        assertEquals("TODO", store.card(a.id).status);
    }

    @Test
    void parallelInvestigationReplanningAndVerificationUseTrackedOrdinarySessions()
            throws Exception {
        Profile p = profile("Long work");
        p.enabled = false;
        service.save(p);
        Conversation c = service.newConversation(p.id, "Goal");
        SessionRecord first = service.createProjectSession(p.id, new CreateSessionRequest());
        CreateSessionRequest same = new CreateSessionRequest();
        same.setProjectId(first.getProjectId());
        SessionRecord second = service.createProjectSession(p.id, same);
        Card root = planned(p, c, "Deliver verified feature", null);
        Card a = planned(p, c, "Inspect code", root.id),
                b = planned(p, c, "Inspect tests", root.id);
        model.longReadsEntered = new CountDownLatch(2);
        model.releaseLongReads = new CountDownLatch(1);
        Inbox one, two;
        try {
            one =
                    service.submitProject(
                            p.id,
                            c.sessionId,
                            first.getSessionId(),
                            "long-task-read code",
                            null,
                            a.id,
                            true);
            two =
                    service.submitProject(
                            p.id,
                            c.sessionId,
                            second.getSessionId(),
                            "long-task-read tests",
                            null,
                            b.id,
                            true);
            assertTrue(
                    model.longReadsEntered.await(5, TimeUnit.SECONDS),
                    "Both sessions should execute together");
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            service.submitProject(
                                    p.id,
                                    c.sessionId,
                                    first.getSessionId(),
                                    "Conflicting write",
                                    null,
                                    null,
                                    false));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            service.submitProject(
                                    p.id,
                                    c.sessionId,
                                    second.getSessionId(),
                                    "Duplicate task",
                                    null,
                                    a.id,
                                    true));
        } finally {
            model.releaseLongReads.countDown();
        }
        await(
                () ->
                        !"WAITING".equals(store.message(one.id).status)
                                && !"WAITING".equals(store.message(two.id).status));
        assertEquals(a.id, store.message(one.id).taskId);
        assertEquals(one.projectRunId, store.card(a.id).lastRunId);
        Card acceptedA = store.card(a.id);
        acceptedA.status = "DONE";
        acceptedA.evidence.add(evidence(first.getSessionId()));
        service.saveCard(acceptedA, p.id);
        Card acceptedB = store.card(b.id);
        acceptedB.status = "DONE";
        acceptedB.evidence.add(evidence(second.getSessionId()));
        service.saveCard(acceptedB, p.id);
        Card implementation = planned(p, c, "Implement against findings", root.id);
        implementation.dependsOn = Arrays.asList(a.id, b.id);
        service.saveCard(implementation, p.id);
        Inbox failed =
                service.submitProject(
                        p.id,
                        c.sessionId,
                        first.getSessionId(),
                        "long-task-failure",
                        null,
                        implementation.id,
                        false);
        await(() -> !"WAITING".equals(store.message(failed.id).status));
        assertTrue(store.message(failed.id).text.contains("FAIL: baseline assumption was wrong"));
        Card adjusted = store.card(implementation.id);
        adjusted.description = "Use corrected input contract";
        adjusted.progress = "Failed baseline; revise implementation and rerun validation";
        service.saveCard(adjusted, p.id);
        Card staleProof = store.card(implementation.id);
        staleProof.status = "DONE";
        staleProof.evidence.add(evidence(first.getSessionId()));
        assertThrows(IllegalArgumentException.class, () -> service.saveCard(staleProof, p.id));
        Inbox verified =
                service.submitProject(
                        p.id,
                        c.sessionId,
                        second.getSessionId(),
                        "Implement correction and verify",
                        null,
                        implementation.id,
                        false);
        await(() -> !"WAITING".equals(store.message(verified.id).status));
        Card accepted = store.card(implementation.id);
        accepted.status = "DONE";
        accepted.evidence.add(evidence(second.getSessionId()));
        service.saveCard(accepted, p.id);
        Card complete = store.card(root.id);
        complete.status = "DONE";
        complete.evidence.add(evidence(second.getSessionId()));
        service.saveCard(complete, p.id);
        assertEquals(2, service.taskExecutions(p.id, implementation.id).size());
        assertFalse(
                service.taskContext(p.id, c.sessionId).contains("Use corrected input contract"));
        assertNotEquals(
                store.message(failed.id).taskRequirements,
                store.message(verified.id).taskRequirements);
    }

    @Test
    void runningProjectsAcceptSteeringAndStopWithoutCrossOwnerControl() throws Exception {
        Profile p = profile("Steering");
        p.enabled = false;
        service.save(p);
        Conversation c = service.newConversation(p.id, "Steer");
        SessionRecord project = service.createProjectSession(p.id, new CreateSessionRequest());
        model.longReadsEntered = new CountDownLatch(1);
        model.releaseLongReads = new CountDownLatch(1);
        Inbox watch;
        try {
            watch =
                    service.submitProject(
                            p.id, c.sessionId, project.getSessionId(), "long-task-read steering");
            assertTrue(model.longReadsEntered.await(5, TimeUnit.SECONDS));
            service.steerProject(
                    p.id,
                    project.getSessionId(),
                    watch.projectRunId,
                    "Use revised acceptance criteria");
            Profile other = profile("Unrelated controller");
            assertThrows(
                    PersonalAgentException.class,
                    () ->
                            service.stopProject(
                                    other.id, project.getSessionId(), watch.projectRunId));
            assertThrows(
                    ProtocolException.class,
                    () -> service.stopProject(p.id, project.getSessionId(), "wrong-run"));
            assertEquals(
                    true,
                    service.stopProject(p.id, project.getSessionId(), watch.projectRunId)
                            .get("stopRequested"));
        } finally {
            model.releaseLongReads.countDown();
        }
        await(() -> !"WAITING".equals(store.message(watch.id).status));
        assertTrue(store.message(watch.id).text.contains("CANCELLED"));
    }

    @Test
    void readyTaskGetsOneContinuationAndWaitingTaskStaysQuiet() throws Exception {
        Profile p = profile("Continuation");
        p.enabled = false;
        service.save(p);
        Conversation c = service.newConversation(p.id, "Resume");
        Card task = planned(p, c, "Inspect unfinished work", null);
        p.enabled = true;
        service.save(p);
        service.tick();
        await(
                () ->
                        store.messages(c.sessionId).stream()
                                .anyMatch(
                                        m ->
                                                "CONTINUE".equals(m.source)
                                                        && "DONE".equals(m.status)));
        service.tick();
        service.tick();
        assertEquals(
                1,
                store.messages(c.sessionId).stream()
                        .filter(m -> "CONTINUE".equals(m.source))
                        .count());
        Card waiting = store.card(task.id);
        waiting.status = "WAITING";
        waiting.progress = "Need user input";
        service.saveCard(waiting, p.id);
        service.tick();
        assertEquals(
                1,
                store.messages(c.sessionId).stream()
                        .filter(m -> "CONTINUE".equals(m.source))
                        .count());
    }

    @Test
    void restartInspectsUncertainProjectSubmissionWithoutReplayingIt() throws Exception {
        Profile p = profile("Unknown project outcome");
        p.enabled = false;
        service.save(p);
        Conversation c = service.newConversation(p.id, "Recovery");
        SessionRecord project = service.createProjectSession(p.id, new CreateSessionRequest());
        Inbox run = new Inbox();
        run.id = "uncertain_" + UUID.randomUUID();
        run.agentId = p.id;
        run.sessionId = c.sessionId;
        run.projectSessionId = project.getSessionId();
        run.projectRunId = "run_" + UUID.randomUUID();
        run.status = "WAITING";
        run.source = "PROJECT";
        run.dispatchStarted = true;
        run.text = "Do not duplicate this operation";
        store.enqueue(run);
        service.tick();
        assertEquals("PENDING", store.message(run.id).status);
        assertTrue(history.findMessages(project.getSessionId()).isEmpty());
        assertTrue(
                store.message(run.id).text.contains("inspect")
                        || store.message(run.id).text.contains("Inspect"));
    }

    @Test
    void agentToolLoopReplansAfterFailureAndCompletesWithFreshEvidence() throws Exception {
        Profile p = profile("Adaptive loop");
        p.enabled = false;
        service.save(p);
        Conversation c = service.newConversation(p.id, "Complex goal");
        SessionRecord project = service.createProjectSession(p.id, new CreateSessionRequest());
        Card task = planned(p, c, "Deliver against verified criteria", null);
        java.util.Deque<java.util.function.Function<ModelRequest, ModelResponse>> flow =
                new java.util.concurrent.ConcurrentLinkedDeque<>();
        flow.add(
                req -> {
                    assertTrue(
                            req.getMessages().stream()
                                    .anyMatch(
                                            m ->
                                                    AgentMessage.isRuntimeContext(m)
                                                            && m.getContent()
                                                                    .contains(task.title)));
                    return call(
                            "projects",
                            json.createObjectNode()
                                    .put("action", "send")
                                    .put("taskId", task.id)
                                    .put("sessionId", project.getSessionId())
                                    .put("text", "long-task-failure"));
                });
        flow.add(req -> reply("Work started"));
        flow.add(
                req -> {
                    assertTrue(
                            req.getMessages()
                                    .get(req.getMessages().size() - 1)
                                    .getContent()
                                    .contains("FAIL: baseline assumption was wrong"));
                    Card current = store.card(task.id);
                    return call(
                            "tasks",
                            json.createObjectNode()
                                    .put("action", "save")
                                    .put("id", task.id)
                                    .put("revision", current.revision)
                                    .put("title", current.title)
                                    .put("status", "DOING")
                                    .put(
                                            "description",
                                            "Correct the assumption, then validate the result")
                                    .put(
                                            "progress",
                                            "Initial check failed; adjust the implementation"));
                });
        flow.add(
                req ->
                        call(
                                "projects",
                                json.createObjectNode()
                                        .put("action", "send")
                                        .put("taskId", task.id)
                                        .put("sessionId", project.getSessionId())
                                        .put(
                                                "text",
                                                "Apply corrected approach and run verification")));
        flow.add(req -> reply(null));
        flow.add(
                req -> {
                    Card current = store.card(task.id);
                    com.fasterxml.jackson.databind.node.ObjectNode args =
                            json.createObjectNode()
                                    .put("action", "save")
                                    .put("id", task.id)
                                    .put("revision", current.revision)
                                    .put("title", current.title)
                                    .put("status", "DONE")
                                    .put(
                                            "progress",
                                            "Verified against unchanged acceptance criteria");
                    args.set(
                            "evidence",
                            json.valueToTree(
                                    Collections.singletonList(evidence(project.getSessionId()))));
                    return call("tasks", args);
                });
        flow.add(req -> reply("Verified result delivered"));
        model.scripts.put(c.sessionId, flow);
        synchronized (service) {
            p.enabled = true;
            service.save(p);
            service.send(
                    p.id,
                    c.sessionId,
                    "Solve this goal and verify it",
                    "adaptive_" + UUID.randomUUID());
        }
        service.tick();
        await(() -> "DONE".equals(store.card(task.id).status));
        await(
                () ->
                        store.messages(c.sessionId).stream()
                                .anyMatch(m -> "Verified result delivered".equals(m.answer)));
        assertTrue(flow.isEmpty());
        assertEquals(task.acceptance, store.card(task.id).acceptance);
        assertEquals(2, service.taskExecutions(p.id, task.id).size());
        assertTrue(
                history.findMessages(c.sessionId).stream()
                        .anyMatch(
                                m ->
                                        m.getToolCalls() != null
                                                && m.getToolCalls().stream()
                                                        .anyMatch(
                                                                t -> "tasks".equals(t.getName()))));
    }

    ModelResponse call(String name, com.fasterxml.jackson.databind.JsonNode args) {
        ModelResponse r = new ModelResponse();
        r.setToolCalls(Collections.singletonList(new ToolCall("script", name, args.toString())));
        return r;
    }

    @Test
    void runtimeSnapshotsAppendOnceAndTaskAndMemoryEditsPreserveTheRequestPrefix()
            throws Exception {
        Profile p = profile("Stable prefix");
        p.enabled = false;
        service.save(p);
        Conversation c = service.newConversation(p.id, "Context stability");
        Card task = new Card();
        task.title = "Inspect the environment";
        task.status = "WAITING";
        task.progress = "Awaiting the initial input";
        task.conversationId = c.sessionId;
        service.saveCard(task, p.id);
        List<ModelRequest> seen = new CopyOnWriteArrayList<>();
        java.util.Deque<java.util.function.Function<ModelRequest, ModelResponse>> flow =
                new ConcurrentLinkedDeque<>();
        flow.add(
                req -> {
                    seen.add(req);
                    assertFalse(req.getSystemPrompt().contains("Current time (UTC):"));
                    assertFalse(req.getSystemPrompt().contains("Current task state"));
                    assertFalse(req.getSystemPrompt().contains(p.memory));
                    assertEquals(2, req.getMessages().size());
                    assertTrue(AgentMessage.isRuntimeContext(req.getMessages().get(0)));
                    assertTrue(req.getMessages().get(0).getContent().contains(p.memory));
                    assertEquals("Plan the next step", req.getMessages().get(1).getContent());
                    return call(
                            "tasks",
                            json.createObjectNode()
                                    .put("action", "save")
                                    .put("id", task.id)
                                    .put("revision", store.card(task.id).revision)
                                    .put("title", task.title)
                                    .put("status", "WAITING")
                                    .put("progress", "New input needed before execution"));
                });
        flow.add(
                req -> {
                    seen.add(req);
                    assertEquals(seen.get(0).getSystemPrompt(), req.getSystemPrompt());
                    assertEquals(
                            seen.get(0).getMessages().get(0).getContent(),
                            req.getMessages().get(0).getContent());
                    assertEquals(
                            1,
                            req.getMessages().stream()
                                    .filter(AgentMessage::isRuntimeContext)
                                    .count());
                    assertTrue(
                            req.getMessages()
                                    .get(req.getMessages().size() - 1)
                                    .getContent()
                                    .contains("New input needed"));
                    return call(
                            "memory",
                            json.createObjectNode()
                                    .put("action", "add")
                                    .put("revision", store.profile(p.id).revision)
                                    .put("category", "preference")
                                    .put("content", "Prefer short status updates.")
                                    .put("reason", "User preference"));
                });
        flow.add(
                req -> {
                    seen.add(req);
                    assertEquals(seen.get(0).getSystemPrompt(), req.getSystemPrompt());
                    assertEquals(
                            1,
                            req.getMessages().stream()
                                    .filter(AgentMessage::isRuntimeContext)
                                    .count());
                    return reply("Plan recorded");
                });
        model.scripts.put(c.sessionId, flow);
        Inbox first;
        synchronized (service) {
            p.enabled = true;
            service.save(p);
            first =
                    service.send(
                            p.id, c.sessionId, "Plan the next step", "stable_" + UUID.randomUUID());
        }
        await(() -> "DONE".equals(store.message(first.id).status));
        List<AgentMessage> before = history.findMessages(c.sessionId);
        String oldSnapshot = before.get(0).getContent();
        flow.add(
                req -> {
                    seen.add(req);
                    assertEquals(seen.get(0).getSystemPrompt(), req.getSystemPrompt());
                    assertEquals(
                            2,
                            req.getMessages().stream()
                                    .filter(AgentMessage::isRuntimeContext)
                                    .count());
                    for (int i = 0; i < before.size(); i++) {
                        assertEquals(
                                before.get(i).getMessageId(),
                                req.getMessages().get(i).getMessageId());
                        assertEquals(
                                before.get(i).getContent(), req.getMessages().get(i).getContent());
                    }
                    AgentMessage latest = req.getMessages().get(before.size());
                    assertTrue(AgentMessage.isRuntimeContext(latest));
                    assertTrue(latest.getContent().contains("Prefer short status updates."));
                    assertTrue(latest.getContent().contains("New input needed"));
                    assertTrue(latest.getContent().contains("Current event source: CHECK"));
                    return reply(null);
                });
        Inbox check = new Inbox();
        check.id = "check_" + UUID.randomUUID();
        check.agentId = p.id;
        check.sessionId = c.sessionId;
        check.source = "CHECK";
        check.taskId = task.id;
        check.text = "Review without new facts";
        check.createdAt = System.currentTimeMillis();
        store.enqueue(check);
        await(() -> "DONE".equals(store.message(check.id).status));
        assertEquals(4, seen.size());
        assertEquals(oldSnapshot, history.findMessages(c.sessionId).get(0).getContent());
        assertFalse(oldSnapshot.contains("Prefer short status updates."));
    }

    ModelResponse reply(String text) {
        ModelResponse r = new ModelResponse();
        r.setContent(json.createObjectNode().put("userReply", text).toString());
        return r;
    }

    @TestConfiguration
    static class Fixtures {
        @Bean
        Model model() {
            return new Model();
        }
    }

    static class Model implements ModelProvider {
        final List<ModelRequest> requests = new CopyOnWriteArrayList<>();
        volatile CountDownLatch longReadsEntered, releaseLongReads;
        final Map<String, java.util.Deque<java.util.function.Function<ModelRequest, ModelResponse>>>
                scripts = new ConcurrentHashMap<>();

        public String getName() {
            return "personal-test";
        }

        public ModelResponse chat(ModelRequest request) {
            requests.add(request);
            ModelResponse response = new ModelResponse();
            AgentMessage last = request.getMessages().get(request.getMessages().size() - 1);
            boolean personal = request.getSystemPrompt().contains("Personal Agent:");
            if (personal) {
                java.util.Deque<java.util.function.Function<ModelRequest, ModelResponse>> script =
                        scripts.get(last.getSessionId());
                if (script != null && !script.isEmpty()) return script.removeFirst().apply(request);
            }
            if (!personal
                    && last.getContent() != null
                    && last.getContent().contains("long-task-read")) {
                longReadsEntered.countDown();
                try {
                    if (!releaseLongReads.await(8, TimeUnit.SECONDS))
                        throw new IllegalStateException("Timed out waiting for fixture");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            if (!personal
                    && last.getContent() != null
                    && last.getContent().contains("long-task-failure")) {
                response.setContent("FAIL: baseline assumption was wrong");
                return response;
            }
            if (personal
                    && last.getContent() != null
                    && last.getContent().startsWith("[CHECK]")
                    && last.getContent().contains("quiet-review")) {
                response.setContent("{\"userReply\":null}");
                return response;
            }
            if (personal
                    && "user".equals(last.getRole())
                    && last.getContent().equals("I need a release checklist for my application")) {
                response.setToolCalls(
                        Collections.singletonList(
                                new ToolCall(
                                        "independent-create",
                                        "projects",
                                        "{\"action\":\"open\",\"projectName\":\"Release checklist\",\"title\":\"Implementation\"}")));
                return response;
            }
            if (personal
                    && "tool".equals(last.getRole())
                    && last.getContent().contains("\"projectName\":\"Release checklist\"")) {
                try {
                    String id =
                            new ObjectMapper()
                                    .readTree(last.getContent())
                                    .path("sessionId")
                                    .asText();
                    response.setToolCalls(
                            Collections.singletonList(
                                    new ToolCall(
                                            "independent-send",
                                            "projects",
                                            "{\"action\":\"send\",\"sessionId\":\""
                                                    + id
                                                    + "\",\"text\":\"Implement and verify a release checklist\"}")));
                    return response;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            if (!personal
                    && "user".equals(last.getRole())
                    && last.getContent().contains("remote project work"))
                response.setToolCalls(
                        Collections.singletonList(
                                new ToolCall("remote-call", "remote_read", "{}")));
            else
                response.setContent(
                        personal
                                ? "Reviewed the evidence and updated the plan."
                                : "Project evidence verified.");
            return response;
        }
    }
}
