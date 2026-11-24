package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.core.execution.ConversationService;
import io.github.differentialmanifold.jagentharness.core.execution.ProtocolException;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerService;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerStore;
import io.github.differentialmanifold.jagentharness.core.skill.SkillActivation;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SpringBootTest(
        classes = TestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:sqlite:file:runner-tests?mode=memory&cache=shared",
            "spring.datasource.driver-class-name=org.sqlite.JDBC",
            "spring.datasource.hikari.maximum-pool-size=1",
            "harness.model.provider=scripted",
            "harness.model.model=test",
            "harness.compaction.enabled=false",
            "harness.protocol.token=test-token",
            "harness.store.jdbc.application-id=runner-tests"
        })
@Import(ConversationProtocolTest.Fixtures.class)
class RunnerProtocolTest {
    @Autowired RunnerService runners;
    @Autowired RunnerStore store;
    @Autowired SessionManager sessions;
    @Autowired SessionStore history;
    @Autowired ConversationService chat;
    @Autowired ConversationProtocolTest.Scripted model;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @LocalServerPort int port;
    final ObjectMapper json = new ObjectMapper();
    final String secret = "secret-for-tests-with-at-least-32-characters";

    @Test
    void browserSkillsFollowTheProjectRunnerAndItsBashSelection() throws Exception {
        RunnerInfo runner = register();
        TestHttpClient api = http(runner.runnerId);
        ToolDescriptor bash = new ToolDescriptor();
        bash.name = "bash";
        bash.parameters.put("type", "object");
        runner.tools.add(bash);
        api.management("POST", "/api/v1/runners", runner);

        String session = request(runner).sessionId;
        Map<String, String> chat = Collections.singletonMap("sessionId", session);
        String project = sessions.requireSession(session).getProjectId();
        String main = "skills/browser-research/SKILL.md";
        String reference = "skills/playwright-cli/references/session-management.md";
        String files = "/api/v1/vfs/files?scope=project&sessionId=" + session + "&prefix=";
        assertEquals(0, api.management("GET", files + main, null).size());
        assertEquals(
                0,
                api.management("GET", "/api/v1/vfs/files?scope=global&prefix=" + main, null)
                        .size());

        runner.capabilities.add("coding");
        api.management("POST", "/api/v1/runners", runner);
        String prompt =
                api.management("POST", "/api/v1/agent/prompt-preview", chat)
                        .path("systemPrompt")
                        .asText();
        assertTrue(prompt.contains(main));
        assertTrue(prompt.contains("skills/playwright-cli/SKILL.md"));
        assertFalse(prompt.contains("npx --yes"));
        assertTrue(api.management("GET", files + main, null).get(0).path("readOnly").asBoolean());
        String read = "/api/v1/vfs/files/content?scope=project&sessionId=" + session + "&path=";
        assertEquals("builtin", api.management("GET", read + main, null).path("source").asText());
        Map<String, Object> call = new LinkedHashMap<>(chat);
        call.put("toolName", "skill");
        call.put("arguments", Collections.singletonMap("path", "skills/playwright-cli/SKILL.md"));
        String cliSkill =
                json.readTree(api.management("POST", "/api/v1/tools/call", call).path("result").asText())
                        .path("content")
                        .asText();
        String sessionName = "ja-" + SkillActivation.digest(session).substring(0, 20);
        assertTrue(cliSkill.contains("playwright-cli -s=" + sessionName + " snapshot"));
        assertFalse(cliSkill.contains("{{browserSession}}"));
        call.put("arguments", Collections.singletonMap("path", reference));
        JsonNode result =
                json.readTree(
                        api.management("POST", "/api/v1/tools/call", call).path("result").asText());
        assertEquals(
                api.management("GET", read + reference, null).path("content"),
                result.path("content"));
        assertFalse(result.path("content").asText().isEmpty());

        // A previously imported reference must read identically in the UI and via the skill tool.
        String defaults = "/api/v1/vfs/files?scope=global";
        Map<String, String> inherited = new LinkedHashMap<>();
        inherited.put("path", reference);
        inherited.put("content", "Local browser reference");
        try {
            api.management("PUT", defaults, inherited);
            assertEquals(
                    "global",
                    api.management("GET", files + reference, null).get(0).path("source").asText());
            assertEquals(
                    "Local browser reference",
                    api.management("GET", read + reference, null).path("content").asText());
            assertEquals(
                    "Local browser reference",
                    json.readTree(
                                    api.management("POST", "/api/v1/tools/call", call)
                                            .path("result")
                                            .asText())
                            .path("content")
                            .asText());
        } finally {
            api.management("DELETE", defaults + "&path=" + reference, null);
        }

        String tools = "/api/v1/runners/projects/" + project + "/tools";
        api.management(
                "PUT", tools, Collections.singletonMap("enabledTools", Collections.emptyList()));
        assertEquals(0, api.management("GET", files + main, null).size());
        assertFalse(
                api.management("POST", "/api/v1/agent/prompt-preview", chat)
                        .path("systemPrompt")
                        .asText()
                        .contains(main));
        api.management(
                "PUT",
                tools,
                Collections.singletonMap("enabledTools", Collections.singletonList("bash")));

        Map<String, String> custom = new LinkedHashMap<>();
        custom.put("path", main);
        custom.put(
                "content",
                "---\nname: browser-research\ndescription: Project preference\n---\nUse the company portal.");
        api.management("PUT", "/api/v1/vfs/files?scope=project&sessionId=" + session, custom);
        assertFalse(api.management("GET", files + main, null).get(0).path("readOnly").asBoolean());
        call.put("arguments", Collections.singletonMap("path", main));
        assertEquals(
                custom.get("content"),
                json.readTree(
                                api.management("POST", "/api/v1/tools/call", call)
                                        .path("result")
                                        .asText())
                        .path("content")
                        .asText());
    }

    @Test
    void optionalBuildMetadataSurvivesPersistenceHeartbeatAndMixedVersionReconnects()
            throws Exception {
        RunnerInfo legacy = register();
        assertNull(store.runner(legacy.runnerId).version);
        assertNull(store.runner(legacy.runnerId).protocolVersion);
        RunnerInfo modern = register();
        modern.version = "2.3.0";
        modern.buildRevision = "commit-one";
        modern.protocolVersion = "1";
        JsonNode registered = http(modern.runnerId).management("POST", "/api/v1/runners", modern);
        assertEquals(modern.version, registered.path("version").asText());
        io.github.differentialmanifold.jagentharness.store.jdbc.JdbcStoreProperties properties =
                new io.github.differentialmanifold.jagentharness.store.jdbc.JdbcStoreProperties();
        properties.setApplicationId("runner-tests");
        RunnerStore reopened =
                new io.github.differentialmanifold.jagentharness.store.jdbc.JdbcRunnerStore(
                        db, json, properties);
        for (RunnerInfo info : Arrays.asList(legacy, modern)) {
            CompletableFuture<RunnerPoll> poll = runners.poll(info.runnerId, null, 0);
            try {
                RunnerInfo persisted = reopened.runner(info.runnerId);
                assertEquals(info.version, persisted.version);
                assertEquals(info.buildRevision, persisted.buildRevision);
                assertEquals(info.protocolVersion, persisted.protocolVersion);
                assertTrue(persisted.lastSeen > 0);
                JsonNode listed = http(info.runnerId).management("GET", "/api/v1/runners", null);
                JsonNode row = null;
                for (JsonNode candidate : listed)
                    if (info.runnerId.equals(candidate.path("runnerId").asText())) row = candidate;
                assertNotNull(row);
                assertEquals(
                        info.version,
                        row.path("version").isNull() ? null : row.path("version").asText());
            } finally {
                runners.disconnect(info.runnerId);
                poll.get(2, TimeUnit.SECONDS);
            }
        }
        modern.version = "2.4.0";
        modern.buildRevision = "commit-two";
        http(modern.runnerId).management("POST", "/api/v1/runners", modern);
        assertEquals("commit-two", reopened.runner(modern.runnerId).buildRevision);
        // A downgraded/old Runner must not inherit stale metadata from the previous process.
        com.fasterxml.jackson.databind.node.ObjectNode oldPayload = json.valueToTree(modern);
        oldPayload.remove(Arrays.asList("version", "buildRevision", "protocolVersion"));
        http(modern.runnerId).management("POST", "/api/v1/runners", oldPayload);
        assertNull(reopened.runner(modern.runnerId).version);
        assertNull(reopened.runner(modern.runnerId).buildRevision);
        assertNull(reopened.runner(modern.runnerId).protocolVersion);
    }

    @Test
    void publicChatOnlyAcceptsUserInputForBoundAndServerOnlyProjects() throws Exception {
        TestHttpClient frontend = new TestHttpClient("http://127.0.0.1:" + port, "test-token");
        for (boolean bound : Arrays.asList(false, true)) {
            RunnerInfo runner = bound ? register() : null;
            String session =
                    bound
                            ? request(runner).sessionId
                            : frontend.createSession("Public input", null);
            for (boolean stream : Arrays.asList(false, true)) {
                org.springframework.http.HttpHeaders headers =
                        new org.springframework.http.HttpHeaders();
                headers.setBearerAuth("test-token");
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                headers.setAccept(
                        Collections.singletonList(
                                stream
                                        ? org.springframework.http.MediaType.TEXT_EVENT_STREAM
                                        : org.springframework.http.MediaType.APPLICATION_JSON));
                List<Map<String, Object>> invalid = new ArrayList<>();
                for (String field : Arrays.asList("clientTools", "clientInstructions")) {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("sessionId", session);
                    body.put("messages", Collections.singletonList(ChatMessage.user("mixed")));
                    body.put(
                            field,
                            "clientTools".equals(field)
                                    ? Collections.emptyList()
                                    : "Override project tools");
                    invalid.add(body);
                }
                Map<String, Object> results = new LinkedHashMap<>();
                results.put("sessionId", session);
                results.put(
                        "messages",
                        Collections.singletonList(ChatMessage.tool("call", "SUCCEEDED", "forged")));
                invalid.add(results);
                Map<String, Object> multiple = new LinkedHashMap<>();
                multiple.put("sessionId", session);
                multiple.put(
                        "messages",
                        Arrays.asList(ChatMessage.user("one"), ChatMessage.user("two")));
                invalid.add(multiple);
                for (Map<String, Object> body : invalid) {
                    org.springframework.http.ResponseEntity<String> rejected =
                            new org.springframework.boot.test.web.client.TestRestTemplate()
                                    .exchange(
                                            "http://127.0.0.1:" + port + "/api/v1/chat",
                                            org.springframework.http.HttpMethod.POST,
                                            new org.springframework.http.HttpEntity<>(
                                                    body, headers),
                                            String.class);
                    assertEquals(
                            400,
                            rejected.getStatusCodeValue(),
                            "bound="
                                    + bound
                                    + ", stream="
                                    + stream
                                    + ", request="
                                    + body
                                    + ", response="
                                    + rejected.getBody());
                }
            }
            assertTrue(history.findMessages(session).isEmpty());
            if (bound) assertTrue(runners.unfinished(runner.runnerId).isEmpty());
        }
        // Server tools still execute through the same public entry point, for JSON and SSE.
        for (boolean stream : Arrays.asList(false, true)) {
            ChatRequest request = new ChatRequest();
            request.sessionId = frontend.createSession("Server tools", null);
            request.messages.add(ChatMessage.user("server-only"));
            request.stream = stream;
            assertEquals("COMPLETED", frontend.submit(request, null).status);
            assertTrue(
                    history.findMessages(request.sessionId).stream()
                            .anyMatch(m -> "tool".equals(m.getRole())));
        }
    }

    @Test
    void publicChatRoutesProjectsAndPrivateChatOnlyAdvancesClaimedRunnerWork() throws Exception {
        TestHttpClient frontend = new TestHttpClient("http://127.0.0.1:" + port, "test-token");
        ChatRequest serverOnly = new ChatRequest();
        serverOnly.sessionId = frontend.createSession("Server tools", null);
        serverOnly.messages.add(ChatMessage.user("server-only"));
        assertEquals("COMPLETED", frontend.submit(serverOnly, null).status);
        assertEquals(
                403,
                assertThrows(
                                TestHttpClient.HttpError.class,
                                () ->
                                        frontend.management(
                                                "POST", "/api/v1/runners/chat", serverOnly))
                        .status);

        for (boolean stream : Arrays.asList(false, true)) {
            RunnerInfo runner = register();
            TestHttpClient executor = http(runner.runnerId);
            ChatRequest request = request(runner);
            request.runId = null; // Public callers need only a session and message.
            request.stream = stream;
            ExecutorService worker = Executors.newSingleThreadExecutor();
            List<StreamEvent> events = new CopyOnWriteArrayList<>();
            try {
                Future<ChatResponse> reply =
                        worker.submit(() -> frontend.submit(request, events::add));
                RunnerPoll poll =
                        json.treeToValue(
                                executor.management(
                                        "GET",
                                        "/api/v1/runners/" + runner.runnerId + "/poll",
                                        null),
                                RunnerPoll.class);
                assertNotNull(poll.job);
                assertEquals(request.sessionId, poll.job.request.sessionId);
                assertEquals("RUNNING", frontend.state(request.sessionId).status);
                assertTrue(history.findMessages(request.sessionId).isEmpty());

                ChatResponse tools = executor.chat(poll.job.request, null);
                assertEquals("REQUIRES_ACTION", tools.status);
                assertEquals("REQUIRES_ACTION", executor.state(request.sessionId).status);
                ChatRequest results = new ChatRequest();
                results.sessionId = request.sessionId;
                results.runId = tools.runId;
                for (ToolInvocation call : tools.toolCalls)
                    results.messages.add(
                            ChatMessage.tool(call.toolCallId, "SUCCEEDED", "local result"));
                assertEquals(
                        400,
                        assertThrows(TestHttpClient.HttpError.class, () -> frontend.submit(results, null))
                                .status);
                ChatResponse answer = executor.chat(results, null);
                assertEquals("COMPLETED", answer.status);
                executor.management(
                        "POST", "/api/v1/runners/" + runner.runnerId + "/complete", answer);
                ChatResponse received = reply.get(5, TimeUnit.SECONDS);
                assertEquals(answer.answer, received.answer);
                assertEquals(answer.runId, received.runId);
                assertEquals(
                        1,
                        history.findMessages(request.sessionId).stream()
                                .filter(m -> "user".equals(m.getRole()))
                                .count());
                if (stream) assertTrue(events.stream().anyMatch(e -> "agent_start".equals(e.type)));
            } finally {
                worker.shutdownNow();
            }
        }
    }

    @Test
    void observingOrDisconnectingDoesNotReplaceOtherObserversOrCancelWork() throws Exception {
        RunnerInfo runner = register();
        ChatRequest request = request(runner);
        io.github.differentialmanifold.jagentharness.core.execution.RunHandle first =
                runners.submit(request);
        io.github.differentialmanifold.jagentharness.core.execution.RunHandle second =
                runners.watch(request.runId, null);
        first.close();
        assertFalse(store.job(request.runId).cancelled);
        runners.cancel(request.runId);
        assertEquals("CANCELLED", second.result().get(5, TimeUnit.SECONDS).status);
        assertEquals(
                "CANCELLED",
                runners.watch(request.runId, null).result().get(5, TimeUnit.SECONDS).status);
    }

    TestHttpClient http(String id) throws Exception {
        return new TestHttpClient("http://127.0.0.1:" + port, "test-token")
                .runnerProtocol()
                .header("X-Runner-Id", id)
                .header("X-Runner-Secret", secret);
    }

    RunnerInfo register() throws Exception {
        RunnerInfo info = new RunnerInfo();
        info.runnerId = "runner_" + UUID.randomUUID().toString();
        info.name = "test runner";
        info.workspaces = true;
        for (String name : Arrays.asList("client_a", "client_b")) {
            ToolDescriptor tool = new ToolDescriptor();
            tool.name = name;
            tool.parameters.put("type", "object");
            info.tools.add(tool);
        }
        http(info.runnerId).management("POST", "/api/v1/runners", info);
        return info;
    }

    ChatRequest request(RunnerInfo info) throws Exception {
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("title", "Remote test");
        create.put("runnerId", info.runnerId);
        create.put("workspacePath", "/remote/workspace");
        JsonNode session = http(info.runnerId).management("POST", "/api/v1/sessions", create);
        assertEquals(info.runnerId, session.path("runnerId").asText());
        assertEquals("/remote/workspace", session.path("workspacePath").asText());
        ChatRequest request = new ChatRequest();
        request.sessionId = session.path("sessionId").asText();
        request.runId = "run_" + UUID.randomUUID();
        request.messages.add(ChatMessage.user("mixed"));
        return request;
    }

    @Test
    void projectsPersistIndependentWorkspacesAndToolSelections() throws Exception {
        RunnerInfo info = register();
        ChatRequest first = request(info);
        String projectId = sessions.requireSession(first.sessionId).getProjectId();
        TestHttpClient frontend = http(info.runnerId);
        frontend.management(
                "PUT",
                "/api/v1/runners/projects/" + projectId + "/tools",
                Collections.singletonMap("enabledTools", Collections.singletonList("client_a")));
        // A submitted catalog cannot enable a project-disabled tool.
        first.clientTools = info.tools;
        runners.submit(first);
        RunnerJob frozen = store.job(first.runId);
        assertEquals("/remote/workspace", frozen.workspace);
        assertEquals(1, frozen.request.clientTools.size());
        assertEquals("client_a", frozen.request.clientTools.get(0).name);
        frontend.management(
                "PUT",
                "/api/v1/runners/projects/" + projectId + "/tools",
                Collections.singletonMap("enabledTools", Collections.emptyList()));
        assertEquals(1, store.job(first.runId).request.clientTools.size());
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("projectId", projectId);
        create.put("workspacePath", "/ignored-for-existing-project");
        JsonNode nextSession = frontend.management("POST", "/api/v1/sessions", create);
        assertEquals("/remote/workspace", nextSession.path("workspacePath").asText());
        assertEquals(info.runnerId, nextSession.path("runnerId").asText());
        ChatRequest next = new ChatRequest();
        next.sessionId = nextSession.path("sessionId").asText();
        next.runId = "run_" + UUID.randomUUID();
        next.messages.add(ChatMessage.user("next"));
        runners.submit(next);
        assertTrue(store.job(next.runId).request.clientTools.isEmpty());
        create.remove("projectId");
        create.put("runnerId", info.runnerId);
        create.put("workspacePath", "D:\\another-project");
        JsonNode second = frontend.management("POST", "/api/v1/sessions", create);
        RunnerProject secondProject = store.project(second.path("projectId").asText());
        assertEquals("D:\\another-project", secondProject.workspace);
        assertEquals(2, secondProject.enabledTools.size());
        assertTrue(store.project(projectId).enabledTools.isEmpty());
        JsonNode context =
                frontend.management(
                        "POST",
                        "/api/v1/agent/context",
                        Collections.singletonMap("sessionId", first.sessionId));
        for (JsonNode tool : context.path("tools"))
            assertFalse(tool.path("name").asText().startsWith("client_"));
        JsonNode preview =
                frontend.management(
                        "POST",
                        "/api/v1/agent/prompt-preview",
                        Collections.singletonMap("sessionId", first.sessionId));
        assertFalse(preview.path("prompt").asText().contains("client_a"));
        assertFalse(preview.path("prompt").asText().contains("client_b"));
        assertEquals("/remote/workspace", preview.path("workspaceRoot").asText());
        assertEquals(
                400,
                assertThrows(
                                TestHttpClient.HttpError.class,
                                () ->
                                        frontend.management(
                                                "PUT",
                                                "/api/v1/runners/projects/" + projectId + "/tools",
                                                Collections.emptyMap()))
                        .status);
        assertEquals(
                400,
                assertThrows(
                                TestHttpClient.HttpError.class,
                                () ->
                                        frontend.management(
                                                "PUT",
                                                "/api/v1/runners/projects/" + projectId + "/tools",
                                                Collections.singletonMap(
                                                        "enabledTools",
                                                        Collections.singletonList(
                                                                "not_registered"))))
                        .status);
        runners.cancel(first.runId);
        runners.cancel(next.runId);
    }

    @Test
    void reRegistrationReleasesThePreviousProcessesLongPoll() throws Exception {
        RunnerInfo info = register();
        CompletableFuture<RunnerPoll> previous = runners.poll(info.runnerId, null);
        assertFalse(previous.isDone());
        runners.register(info, secret);
        assertTrue(previous.isDone());
        ChatRequest request = request(info);
        runners.submit(request);
        RunnerPoll next =
                json.treeToValue(
                        http(info.runnerId)
                                .management(
                                        "GET", "/api/v1/runners/" + info.runnerId + "/poll", null),
                        RunnerPoll.class);
        assertEquals(request.runId, next.job.request.runId);
    }

    @Test
    void renamingPreservesRemoteExecutionTarget() throws Exception {
        RunnerInfo info = register();
        ChatRequest request = request(info);
        Map<String, String> rename = new LinkedHashMap<>();
        rename.put("sessionId", request.sessionId);
        rename.put("title", "Title from the first prompt");
        JsonNode renamed =
                http(info.runnerId).management("POST", "/api/v1/sessions/rename", rename);
        assertEquals(info.runnerId, renamed.path("runnerId").asText());
        assertEquals("/remote/workspace", renamed.path("workspacePath").asText());
    }

    @Test
    void interruptedJobAcceptsSavedResultsWithoutReplayingUserMessage() throws Exception {
        RunnerInfo info = register();
        TestHttpClient http = http(info.runnerId);
        ChatRequest request = request(info);
        runners.submit(request);
        RunnerPoll claimed =
                json.treeToValue(
                        http.management("GET", "/api/v1/runners/" + info.runnerId + "/poll", null),
                        RunnerPoll.class);
        ChatResponse pending = http.chat(claimed.job.request, null);
        assertEquals("REQUIRES_ACTION", pending.status);
        ChatResponse interrupted = new ChatResponse();
        interrupted.runId = request.runId;
        interrupted.sessionId = request.sessionId;
        interrupted.status = "INTERRUPTED";
        http.management("POST", "/api/v1/runners/" + info.runnerId + "/complete", interrupted);
        assertThrows(ProtocolException.class, () -> runners.submit(request));
        String recoverPath =
                "/api/v1/runners/" + info.runnerId + "/runs/" + request.runId + "/recover";
        http.management("POST", recoverPath, Collections.emptyMap());
        assertEquals(
                409,
                assertThrows(
                                TestHttpClient.HttpError.class,
                                () -> http.management("POST", recoverPath, Collections.emptyMap()))
                        .status);
        ChatRequest results = new ChatRequest();
        results.sessionId = request.sessionId;
        results.runId = request.runId;
        for (ToolInvocation tool : pending.toolCalls)
            results.messages.add(
                    ChatMessage.tool(tool.toolCallId, "SUCCEEDED", "saved local result"));
        ChatResponse completed = http.chat(results, null);
        assertEquals("COMPLETED", completed.status);
        http.management("POST", "/api/v1/runners/" + info.runnerId + "/complete", completed);
        assertEquals("COMPLETED", store.job(request.runId).status);
        assertEquals(
                1,
                history.findMessages(request.sessionId).stream()
                        .filter(m -> "user".equals(m.getRole()))
                        .count());
        ChatRequest next = new ChatRequest();
        next.sessionId = request.sessionId;
        next.runId = "run_" + UUID.randomUUID();
        next.messages.add(ChatMessage.user("next"));
        runners.submit(next);
        assertEquals("QUEUED", store.job(next.runId).status);
        runners.cancel(next.runId);
    }

    @Test
    void longPollWakesAndClientResultsUseNormalConversation() throws Exception {
        RunnerInfo info = register();
        TestHttpClient http = http(info.runnerId);
        ChatRequest request = request(info);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> poll =
                    executor.submit(
                            () ->
                                    http.management(
                                            "GET",
                                            "/api/v1/runners/" + info.runnerId + "/poll",
                                            null));
            runners.submit(request);
            RunnerPoll claimed = json.treeToValue(poll.get(5, TimeUnit.SECONDS), RunnerPoll.class);
            assertEquals(request.runId, claimed.job.request.runId);
            assertEquals("CLAIMED", store.job(request.runId).status);
            assertThrows(ProtocolException.class, () -> runners.submit(request));
            ChatResponse pending = http.chat(claimed.job.request, null);
            assertEquals("REQUIRES_ACTION", pending.status);
            ChatRequest results = new ChatRequest();
            results.sessionId = pending.sessionId;
            results.runId = pending.runId;
            for (ToolInvocation call : pending.toolCalls)
                results.messages.add(
                        ChatMessage.tool(call.toolCallId, "SUCCEEDED", "local result"));
            ChatResponse result = http.chat(results, null);
            assertEquals("COMPLETED", result.status);
            http.management("POST", "/api/v1/runners/" + info.runnerId + "/complete", result);
            http.management("POST", "/api/v1/runners/" + info.runnerId + "/complete", result);
            assertEquals("COMPLETED", store.job(request.runId).status);
            assertEquals(
                    3,
                    history.findMessages(request.sessionId).stream()
                            .filter(m -> "tool".equals(m.getRole()))
                            .count());
            assertEquals(
                    1,
                    history.findMessages(request.sessionId).stream()
                            .filter(m -> "user".equals(m.getRole()))
                            .count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void queuedCancellationNeverStartsModel() throws Exception {
        RunnerInfo info = register();
        ChatRequest request = request(info);
        runners.submit(request);
        http(info.runnerId).stop(request.runId);
        assertEquals("CANCELLED", store.job(request.runId).response.status);
        assertTrue(history.findMessages(request.sessionId).isEmpty());
    }

    @Test
    void cancellationIsDeliveredWhileWorkerIsBusy() throws Exception {
        RunnerInfo info = register();
        ChatRequest request = request(info);
        runners.submit(request);
        http(info.runnerId).management("GET", "/api/v1/runners/" + info.runnerId + "/poll", null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> waiting =
                    executor.submit(
                            () ->
                                    http(info.runnerId)
                                            .management(
                                                    "GET",
                                                    "/api/v1/runners/"
                                                            + info.runnerId
                                                            + "/poll?activeRun="
                                                            + request.runId,
                                                    null));
            http(info.runnerId).stop(request.runId);
            assertEquals(
                    request.runId,
                    waiting.get(5, TimeUnit.SECONDS).path("cancelledRuns").get(0).asText());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void credentialsAndSessionAssignmentAreEnforced() throws Exception {
        RunnerInfo owner = register(), other = register();
        ChatRequest request = request(owner);
        request.modelId = "default";
        runners.submit(request);
        http(owner.runnerId).management("GET", "/api/v1/runners/" + owner.runnerId + "/poll", null);
        ChatRequest tampered = json.convertValue(request, ChatRequest.class);
        tampered.modelId = "runner-picked-another-model";
        runners.authorizeChat(tampered, owner.runnerId, secret);
        assertEquals("default", tampered.modelId);
        TestHttpClient.HttpError error =
                assertThrows(
                        TestHttpClient.HttpError.class, () -> http(other.runnerId).chat(request, null));
        assertEquals(409, error.status);
        assertEquals(
                400,
                assertThrows(
                                TestHttpClient.HttpError.class,
                                () ->
                                        new TestHttpClient("http://127.0.0.1:" + port, "test-token")
                                                .management("POST", "/api/v1/runners", owner))
                        .status);
        RunnerInfo altered = json.convertValue(owner, RunnerInfo.class);
        altered.workspaces = false;
        assertThrows(ProtocolException.class, () -> runners.register(altered, secret));
        store.heartbeat(other.runnerId, 0);
        ChatRequest offline = request(other);
        runners.submit(offline);
        assertEquals("QUEUED", runners.job(offline.runId).status);
    }

    @Test
    void claimedJobSurvivesAnotherPollWithoutCreatingAnotherTurn() throws Exception {
        RunnerInfo info = register();
        ChatRequest request = request(info);
        runners.submit(request);
        JsonNode first =
                http(info.runnerId)
                        .management("GET", "/api/v1/runners/" + info.runnerId + "/poll", null);
        JsonNode again =
                http(info.runnerId)
                        .management("GET", "/api/v1/runners/" + info.runnerId + "/poll", null);
        assertEquals(first.path("job").path("request"), again.path("job").path("request"));
        assertTrue(history.findMessages(request.sessionId).isEmpty());
    }
}
