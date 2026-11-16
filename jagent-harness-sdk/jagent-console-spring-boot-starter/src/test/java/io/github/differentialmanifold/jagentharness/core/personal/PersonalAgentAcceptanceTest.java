package io.github.differentialmanifold.jagentharness.core.personal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.model.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.project.ProjectService;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.spring.web.personal.*;
import io.github.differentialmanifold.jagentharness.store.jdbc.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PersonalAgentAcceptanceTest {
    @Test
    void completedAndFailedContinuationsStayConsumedAcrossTicksAndRestart() throws Exception {
        try (Fixture f = new Fixture()) {
            Card card = f.task();
            f.runtime.tick();
            f.idle("continue_task_1");
            assertEquals("DONE", f.store.message("continue_task_1").status);
            clearInvocations(f.store);
            for (int i = 0; i < 10; i++) f.runtime.tick();
            verify(f.store, never()).enqueue(any());
            f.restart();
            for (int i = 0; i < 10; i++) f.runtime.tick();
            verify(f.store, never()).enqueue(any());

            Inbox done = f.store.message("continue_task_1");
            done.status = "FAILED";
            f.store.update(done, "DONE");
            f.restart();
            f.runtime.tick();
            verify(f.store, never()).enqueue(any());

            card.progress = "New evidence";
            assertTrue(f.store.save(card));
            f.runtime.tick();
            f.idle("continue_task_2");
            assertEquals(2, f.store.messages("chat").size());
            verify(f.store, times(1)).enqueue(argThat(m -> "continue_task_2".equals(m.id)));
        }
    }

    @Test
    void interruptedContinuationRecoversOnceWithoutReplayingItsRevision() throws Exception {
        try (Fixture f = new Fixture()) {
            f.task();
            Inbox interrupted = f.message("continue_task_1", "RUNNING", "CONTINUE", 1);
            interrupted.taskId = "task";
            f.store.update(interrupted, "RUNNING");
            f.runtime.recoverInterrupted();
            assertEquals("FAILED", f.store.message(interrupted.id).status);
            Inbox recovery = f.store.inbox("PENDING").get(0);
            assertEquals("RECOVERY", recovery.source);
            f.restart();
            f.runtime.tick();
            f.idle(recovery.id);
            clearInvocations(f.store);
            for (int i = 0; i < 10; i++) f.runtime.tick();
            verify(f.store, never()).enqueue(any());
            assertEquals(2, f.store.messages("chat").size());
        }
    }

    @Test
    void explicitModelsAreValidatedBeforeAcceptanceWithoutBreakingReceiptReplay() throws Exception {
        try (Fixture f = new Fixture()) {
            ModelCatalogStore.Configuration c = f.models.configuration();
            ModelProfile disabled = c.models.get(0).copy();
            disabled.id = "disabled";
            disabled.enabled = false;
            c.models.add(disabled);
            f.models.save(c);
            for (String id : Arrays.asList("missing", "disabled")) {
                f.mvc.perform(
                                post(f.path)
                                        .contentType("application/json")
                                        .content(
                                                "{\"text\":\"hello\",\"messageId\":\""
                                                        + id
                                                        + "\",\"modelId\":\""
                                                        + id
                                                        + "\"}"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"));
                assertNull(f.store.message(id));
            }
            f.mvc.perform(
                            post(f.path)
                                    .contentType("application/json")
                                    .content(
                                            "{\"text\":\"hello\",\"messageId\":\"valid\",\"modelId\":\"default\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PENDING"));
            f.runtime.tick();
            f.idle("valid");
            assertEquals("DONE", f.store.message("valid").status);
            assertEquals(
                    "default",
                    f.models.current(
                                    "chat",
                                    PersonalAgentService.messageRunId(f.store.message("valid")))
                            .model
                            .id);

            Inbox accepted =
                    f.service.send(
                            "agent", "chat", "later", "late", "default", Collections.emptyList());
            Inbox omitted = f.service.send("agent", "chat", "default choice", "omitted");
            assertNull(omitted.modelId);
            c = f.models.configuration();
            ModelProfile replacement = c.models.get(0).copy();
            replacement.id = "replacement";
            c.models.add(replacement);
            c.defaultModelId = "replacement";
            c.models.get(0).enabled = false;
            f.models.save(c);
            // A retry retrieves the same receipt even after its model becomes unavailable.
            assertEquals(
                    accepted.id,
                    f.service.send(
                                    "agent",
                                    "chat",
                                    "later",
                                    "late",
                                    "default",
                                    Collections.emptyList())
                            .id);
            assertThrows(
                    PersonalAgentException.class,
                    () ->
                            f.service.send(
                                    "agent",
                                    "chat",
                                    "different",
                                    "late",
                                    "default",
                                    Collections.emptyList()));
            f.runtime.tick();
            f.idle("late");
            assertEquals("FAILED", f.store.message("late").status);
            assertTrue(
                    f.store
                            .message("late")
                            .error
                            .contains("Configured model is unavailable: default"));
            f.runtime.tick();
            f.idle("omitted");
            assertEquals("DONE", f.store.message("omitted").status);
            assertEquals(
                    "replacement",
                    f.models.current("chat", PersonalAgentService.messageRunId(omitted)).model.id);
        }
    }

    @Test
    void summariesExposeFailuresAndPreserveMessageDetailsAndActivityCounts() throws Exception {
        try (Fixture f = new Fixture()) {
            f.mvc.perform(get(f.path + "/status"))
                    .andExpect(jsonPath("$.failedMessages").value(0))
                    .andExpect(jsonPath("$.latestFailure").isEmpty());
            Inbox failed = f.message("failed", "FAILED", "USER", 1);
            failed.error = "Provider unavailable";
            f.store.update(failed, "FAILED");
            f.mvc.perform(get(f.path + "/status"))
                    .andExpect(jsonPath("$.failedMessages").value(1))
                    .andExpect(jsonPath("$.latestFailure.id").value("failed"))
                    .andExpect(jsonPath("$.latestFailure.error").value(failed.error))
                    .andExpect(jsonPath("$.queuedMessages").value(0));
            f.message("done", "DONE", "USER", 2);
            f.message("pending", "PENDING", "USER", 3);
            f.message("running", "RUNNING", "USER", 4);
            f.message("project", "WAITING", "PROJECT", 5);
            f.message("new-failure", "FAILED", "CONTINUE", 6);
            f.message("learning", "FAILED", "LEARNING", 7);
            f.mvc.perform(get(f.path + "/status").param("messageId", "failed"))
                    .andExpect(jsonPath("$.completedMessages").value(1))
                    .andExpect(jsonPath("$.failedMessages").value(2))
                    .andExpect(jsonPath("$.latestFailure.id").value("new-failure"))
                    .andExpect(jsonPath("$.latestFailure.createdAt").value(6))
                    .andExpect(jsonPath("$.queuedMessages").value(1))
                    .andExpect(jsonPath("$.runningMessages").value(1))
                    .andExpect(jsonPath("$.pendingProjectRuns").value(1))
                    .andExpect(jsonPath("$.message.status").value("FAILED"))
                    .andExpect(jsonPath("$.message.error").value(failed.error));
            f.mvc.perform(get("/api/v1/personal/agents/agent/conversations"))
                    .andExpect(jsonPath("$[0].failedMessages").value(2));
        }
    }

    static class Fixture implements AutoCloseable {
        final JdbcTemplate db;
        final JdbcStoreProperties properties = new JdbcStoreProperties();
        PersonalAgentStore store;
        PersonalAgentService service;
        PersonalAgentRuntime runtime;
        MockMvc mvc;
        final String path = "/api/v1/personal/agents/agent/conversations/chat";
        final ModelCatalog models;

        Fixture() throws Exception {
            DriverManagerDataSource source =
                    new DriverManagerDataSource(
                            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            new JdbcSchemaInitializer(source).initialize();
            db = new JdbcTemplate(source);
            properties.setApplicationId("acceptance");
            store = spy(new JdbcPersonalAgentStore(db, new ObjectMapper(), properties));
            Profile agent = new Profile();
            agent.id = "agent";
            agent.name = "Agent";
            store.save(agent);
            Conversation conversation = new Conversation();
            conversation.agentId = agent.id;
            conversation.sessionId = "chat";
            conversation.title = "Chat";
            store.add(conversation);
            ModelProfile model = new ModelProfile();
            model.id = "default";
            model.name = "Default";
            model.model = "test";
            models = new ModelCatalog(new InMemoryModelCatalogStore(), model, p -> null);
            start();
        }

        void start() throws Exception {
            SessionStore history = mock(SessionStore.class);
            SessionRepository records = mock(SessionRepository.class);
            ProjectService projects = mock(ProjectService.class);
            AgentHarness harness = mock(AgentHarness.class);
            when(harness.run(anyString(), anyList(), any(AgentRunOptions.class)))
                    .thenAnswer(
                            call -> {
                                AgentRunOptions options = call.getArgument(2);
                                models.begin(
                                        call.getArgument(0),
                                        options.getRunId(),
                                        options.getModelId());
                                return new AgentRunResult();
                            });
            service =
                    new PersonalAgentService(
                                    store, records, null, history, harness, projects, null, null,
                                    null)
                            .withModels(models);
            PersonalTaskPlan plans = new PersonalTaskPlan(store, records, history);
            runtime =
                    new PersonalAgentRuntime(
                            service,
                            store,
                            records,
                            history,
                            harness,
                            null,
                            plans,
                            new PersonalAgentProjects(service, store, plans, projects, null));
            mvc =
                    MockMvcBuilders.standaloneSetup(
                                    new PersonalAgentController(service, store, null, null))
                            .setControllerAdvice(new PersonalAgentErrors())
                            .build();
        }

        void restart() throws Exception {
            runtime.close();
            service.close();
            // Reopen persisted state, without relying on a runtime-local consumed-revision cache.
            store = spy(new JdbcPersonalAgentStore(db, new ObjectMapper(), properties));
            start();
        }

        Card task() {
            Card c = new Card();
            c.id = "task";
            c.ownerId = "agent";
            c.conversationId = "chat";
            c.title = "Unfinished task";
            c.acceptance = "Verified result";
            assertTrue(store.save(c));
            return c;
        }

        Inbox message(String id, String status, String source, long createdAt) {
            Inbox m = new Inbox();
            m.id = id;
            m.agentId = "agent";
            m.sessionId = "chat";
            m.source = source;
            m.status = status;
            m.text = "Work";
            m.createdAt = createdAt;
            assertTrue(store.enqueue(m));
            return m;
        }

        void idle(String id) throws Exception {
            BooleanSupplier done =
                    () ->
                            store.message(id) != null
                                    && Arrays.asList("DONE", "FAILED")
                                            .contains(store.message(id).status)
                                    && !runtime.isActive("agent");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!done.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
            assertTrue(done.getAsBoolean(), "Turn did not finish: " + id);
        }

        public void close() {
            runtime.close();
            service.close();
        }
    }
}
