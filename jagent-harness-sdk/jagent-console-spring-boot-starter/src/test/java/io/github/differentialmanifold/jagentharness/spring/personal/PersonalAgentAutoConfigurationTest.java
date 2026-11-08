package io.github.differentialmanifold.jagentharness.spring.personal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.conversation.CompactionStore;
import io.github.differentialmanifold.jagentharness.core.execution.RunService;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.message.MessageRepository;
import io.github.differentialmanifold.jagentharness.core.personal.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.project.*;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.core.session.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PersonalAgentAutoConfigurationTest {
    @Test
    void actualProjectExecutionServicesStartWithoutAnyWebClasses() {
        context(mock(PersonalAgentStore.class))
                .withConfiguration(
                        AutoConfigurations.of(
                                io.github.differentialmanifold.jagentharness.spring
                                        .ExecutionAutoConfiguration.class))
                .withBean(RunInputCoordinator.class, () -> mock(RunInputCoordinator.class))
                .withBean(
                        io.github.differentialmanifold.jagentharness.core.runner.RunnerStore.class,
                        () ->
                                mock(
                                        io.github.differentialmanifold.jagentharness.core.runner
                                                .RunnerStore.class))
                .withBean(
                        io.github.differentialmanifold.jagentharness.core.timeline
                                .TimelineEventRepository.class,
                        () ->
                                mock(
                                        io.github.differentialmanifold.jagentharness.core.timeline
                                                .TimelineEventRepository.class))
                .withBean(
                        io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry.class,
                        () ->
                                mock(
                                        io.github.differentialmanifold.jagentharness.core.tool
                                                .ToolRegistry.class))
                .withBean(
                        io.github.differentialmanifold.jagentharness.core.event.AgentEventPublisher
                                .class,
                        () ->
                                mock(
                                        io.github.differentialmanifold.jagentharness.core.event
                                                .AgentEventPublisher.class))
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            assertNotNull(ctx.getBean(PersonalAgentService.class));
                            assertNotNull(
                                    ctx.getBean(
                                            io.github.differentialmanifold.jagentharness.core.runner
                                                    .RunnerService.class));
                            SessionManager sessions = ctx.getBean(SessionManager.class);
                            SessionRecord record = new SessionRecord();
                            record.setSessionId("headless");
                            when(sessions.createSession(any(), any(), any(), any(), any()))
                                    .thenReturn(record);
                            when(sessions.requireSession("headless")).thenReturn(record);
                            assertSame(
                                    record,
                                    ctx.getBean(ProjectService.class)
                                            .createSession(null, new CreateSessionRequest()));
                            AgentRunResult outcome = new AgentRunResult();
                            outcome.setSessionId("headless");
                            outcome.setRunId("run_headless");
                            outcome.setAnswer("Executed without MVC");
                            when(ctx.getBean(AgentHarness.class)
                                            .run(eq("headless"), anyList(), any()))
                                    .thenReturn(outcome);
                            io.github.differentialmanifold.jagentharness.protocol.ChatSubmission
                                    request =
                                            new io.github.differentialmanifold.jagentharness
                                                    .protocol.ChatSubmission();
                            request.sessionId = "headless";
                            request.runId = "run_headless";
                            request.messages.add(
                                    io.github.differentialmanifold.jagentharness.protocol
                                            .ChatMessage.user("Work"));
                            assertEquals(
                                    "Executed without MVC",
                                    ctx.getBean(RunService.class)
                                            .submit(request, null)
                                            .result()
                                            .get(5, java.util.concurrent.TimeUnit.SECONDS)
                                            .answer);
                        });
    }

    private ApplicationContextRunner context(PersonalAgentStore store) {
        return new ApplicationContextRunner()
                .withClassLoader(
                        new FilteredClassLoader(
                                "org.springframework.web",
                                "javax.servlet",
                                "io.github.differentialmanifold.jagentharness.spring.web"))
                .withConfiguration(AutoConfigurations.of(PersonalAgentAutoConfiguration.class))
                .withBean(PersonalAgentStore.class, () -> store)
                .withBean(SessionRepository.class, () -> mock(SessionRepository.class))
                .withBean(SessionManager.class, () -> mock(SessionManager.class))
                .withBean(SessionStore.class, () -> mock(SessionStore.class))
                .withBean(AgentHarness.class, () -> mock(AgentHarness.class))
                .withBean(KnowledgeFileStore.class, () -> mock(KnowledgeFileStore.class))
                .withBean(MessageRepository.class, () -> mock(MessageRepository.class))
                .withBean(ModelProviderRegistry.class, () -> mock(ModelProviderRegistry.class))
                .withBean(AgentSettings.class, () -> mock(AgentSettings.class))
                .withBean(CompactionStore.class, () -> mock(CompactionStore.class))
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    @Test
    void startsWithoutWebAndUsesHostProjectService() {
        PersonalAgentStore store = mock(PersonalAgentStore.class);
        Profile profile = new Profile();
        profile.id = "agent";
        profile.name = "Developer";
        when(store.profile(profile.id)).thenReturn(profile);
        ProjectService operations = mock(ProjectService.class);
        SessionRecord expected = new SessionRecord();
        expected.setSessionId("project-session");
        when(operations.createSession(eq(profile.id), any())).thenReturn(expected);
        context(store)
                .withBean(ProjectService.class, () -> operations)
                .withBean(RunService.class, () -> mock(RunService.class))
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            assertThrows(
                                    ClassNotFoundException.class,
                                    () -> ctx.getClassLoader().loadClass("javax.servlet.Servlet"));
                            PersonalAgentService service = ctx.getBean(PersonalAgentService.class);
                            assertNotNull(ctx.getBean(PersonalAgentTools.class));
                            assertNotNull(ctx.getBean(PersonalAgentLearning.class));
                            assertSame(
                                    expected,
                                    service.createProjectSession(
                                            profile.id, new CreateSessionRequest()));
                            service.tick();
                            verify(operations).createSession(eq(profile.id), any());
                            verify(store, atLeastOnce()).inbox("PENDING");
                        }); // Closing the context also closes the Agent runtime.
    }

    @Test
    void requiresExecutionServicesAndHonorsTheFeatureSwitch() {
        context(mock(PersonalAgentStore.class))
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            assertTrue(ctx.getBeansOfType(PersonalAgentService.class).isEmpty());
                        });
        context(mock(PersonalAgentStore.class))
                .withBean(ProjectService.class, () -> mock(ProjectService.class))
                .withBean(RunService.class, () -> mock(RunService.class))
                .withPropertyValues("harness.personal.enabled=false")
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            assertTrue(ctx.getBeansOfType(PersonalAgentService.class).isEmpty());
                            assertTrue(ctx.getBeansOfType(PersonalAgentTools.class).isEmpty());
                        });
    }
}
