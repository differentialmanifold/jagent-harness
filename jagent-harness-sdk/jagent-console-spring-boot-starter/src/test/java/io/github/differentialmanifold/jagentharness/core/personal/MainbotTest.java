package io.github.differentialmanifold.jagentharness.core.personal;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.project.CreateSessionRequest;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerService;
import io.github.differentialmanifold.jagentharness.protocol.*;
import io.github.differentialmanifold.jagentharness.spring.web.TestHttpClient;
import io.github.differentialmanifold.jagentharness.spring.web.TestServerApplication;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = TestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:mainbot-tests;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "harness.personal.learning.enabled=false",
            "harness.protocol.token=test-token",
            "harness.store.jdbc.application-id=mainbot-tests"
        })
class MainbotTest {
    @Autowired PersonalAgentService service;
    @Autowired PersonalAgentStore store;
    @Autowired PersonalAgentKnowledge knowledge;
    @Autowired RunnerService runners;
    @org.springframework.boot.web.server.LocalServerPort int port;

    @Test
    void startsReadyToChatAndSetupPreservesIdentityAndUserChanges() throws Exception {
        String id = PersonalAgentService.MAINBOT_ID;
        Profile p = service.require(id);
        assertEquals("mainbot", p.name);
        assertNull(p.runnerId);
        assertEquals(id, service.defaults().get("mainbotAgentId"));
        assertEquals(1, store.conversations(id).size());
        String chat = store.conversations(id).get(0).sessionId;
        assertThrows(
                IllegalArgumentException.class,
                () -> service.openProjectSession(id, new CreateSessionRequest(), false));
        // An arbitrary first Runner must not claim mainbot.
        register("runner_phone", false);
        register("runner_coding", true);
        assertNull(service.require(id).runnerId);
        p.name = "Renamed assistant";
        p.instructions = "Keep my preferences.";
        service.save(p);
        service.initializeMainbot();
        assertEquals(chat, store.conversations(id).get(0).sessionId);
        assertEquals(1, store.conversations(id).size());
        assertEquals("Renamed assistant", service.require(id).name);
        assertEquals(3, knowledge.availableSkills(id).size());
        assertThrows(
                IllegalArgumentException.class,
                () -> service.setupMainbot("runner_phone", null, null, null));
        Profile configured = configure("/repo", "/canonical/repo");
        assertEquals("runner_coding", configured.runnerId);
        assertEquals("/canonical/repo", configured.sourceWorkspace);
        long revision = configured.revision;
        assertEquals(revision, configure("/canonical/repo", "/canonical/repo").revision);
        assertEquals("Keep my preferences.", service.require(id).instructions);
        assertThrows(
                PersonalAgentException.class,
                () -> service.setupMainbot("runner_phone", "/elsewhere", "other", "other"));
        assertEquals("/canonical/repo", service.require(id).sourceWorkspace);
        String environment =
                (String)
                        knowledge
                                .availableSkill(
                                        id, "skills/jagent-help/references/environment.json")
                                .get("content");
        assertEquals(
                "/canonical/repo",
                new ObjectMapper().readTree(environment).path("sourceWorkspace").asText());
        assertFalse(
                new ObjectMapper()
                        .readTree(environment)
                        .path("harnessVersion")
                        .asText()
                        .contains("${"));
        Profile ordinary = new Profile();
        ordinary.name = "mainbot";
        ordinary.runnerId = "runner_coding";
        ordinary = service.save(ordinary);
        assertTrue(knowledge.availableSkills(ordinary.id).isEmpty());
        service.initializeMainbot();
        assertEquals(id, service.defaults().get("mainbotAgentId"));
        assertEquals("runner_coding", service.defaults().get("mainbotRunnerId"));
        assertEquals(3, knowledge.availableSkills(id).size());
        assertEquals(1, store.conversations(id).size());
        TestHttpClient api = new TestHttpClient("http://127.0.0.1:" + port, "test-token");
        assertEquals(
                id,
                api.management("GET", "/api/v1/personal/defaults", null)
                        .path("mainbotAgentId")
                        .asText());
    }

    private Profile configure(String requested, String canonical) throws Exception {
        CompletableFuture<Profile> configured =
                CompletableFuture.supplyAsync(
                        () ->
                                service.setupMainbot(
                                        "runner_coding", requested, "2.2.1", "test-revision"));
        RunnerPoll poll = runners.poll("runner_coding", null).get(5, TimeUnit.SECONDS);
        assertNotNull(poll.workspaceRequest);
        assertEquals(requested, poll.workspaceRequest.path);
        WorkspaceListing listing = new WorkspaceListing();
        listing.requestId = poll.workspaceRequest.requestId;
        listing.path = canonical;
        runners.workspaceResult("runner_coding", listing);
        return configured.get(5, TimeUnit.SECONDS);
    }

    private void register(String id, boolean coding) {
        RunnerInfo runner = new RunnerInfo();
        runner.runnerId = id;
        runner.name = id;
        runner.workspaces = coding;
        for (String name :
                coding ? Arrays.asList("read", "bash") : Collections.singletonList("tap")) {
            ToolDescriptor tool = new ToolDescriptor();
            tool.name = name;
            tool.parameters.put("type", "object");
            runner.tools.add(tool);
        }
        runners.register(runner, "a-long-mainbot-test-runner-secret");
    }
}
