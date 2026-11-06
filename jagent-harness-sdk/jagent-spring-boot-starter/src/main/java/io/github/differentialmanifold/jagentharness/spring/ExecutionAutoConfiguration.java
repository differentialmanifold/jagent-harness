package io.github.differentialmanifold.jagentharness.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.execution.*;
import io.github.differentialmanifold.jagentharness.core.project.ProjectService;
import io.github.differentialmanifold.jagentharness.core.runner.*;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.core.timeline.TimelineEventRepository;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

/** Shared execution services also work in applications without Spring MVC. */
@AutoConfiguration(after = AgentHarnessAutoConfiguration.class)
@ConditionalOnBean({AgentHarness.class, RunInputCoordinator.class, TimelineEventRepository.class})
@ConditionalOnProperty(
        prefix = "harness",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ExecutionAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ConversationService conversationService(
            ObjectMapper json,
            AgentHarness harness,
            SessionStore history,
            SessionRepository records,
            TimelineEventRepository timeline,
            ToolRegistry tools,
            RunInputCoordinator inputs,
            AgentEventPublisher publisher) {
        return new ConversationService(
                json, harness, history, records, timeline, tools, inputs, publisher);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(RunnerStore.class)
    @ConditionalOnProperty(
            prefix = "harness.runner",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public RunnerService runnerService(
            RunnerStore store,
            SessionManager sessions,
            ConversationService conversations,
            AgentEventPublisher publisher,
            ObjectMapper json) {
        return new RunnerService(store, sessions, conversations, publisher, json);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProjectService projectService(
            SessionManager sessions, SessionStore history, ObjectProvider<RunnerService> runners) {
        return new ProjectService(sessions, history, runners.getIfAvailable());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public RunService runService(
            ConversationService conversations,
            ObjectProvider<RunnerService> runners,
            SessionManager sessions,
            RunInputCoordinator inputs) {
        return new RunService(conversations, runners.getIfAvailable(), sessions, inputs);
    }
}
