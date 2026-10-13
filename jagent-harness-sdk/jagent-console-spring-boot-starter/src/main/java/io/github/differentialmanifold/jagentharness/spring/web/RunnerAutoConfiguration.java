package io.github.differentialmanifold.jagentharness.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerStore;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
        after = ChatAutoConfiguration.class,
        afterName =
                "io.github.differentialmanifold.jagentharness.store.jdbc.JdbcStoreAutoConfiguration")
@ConditionalOnBean({RunnerStore.class, ChatService.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
        prefix = "harness.runner",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RunnerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public RunnerService runnerService(
            RunnerStore store,
            SessionManager sessions,
            ChatService chat,
            AgentEventPublisher publisher,
            ObjectMapper json) {
        return new RunnerService(store, sessions, chat, publisher, json);
    }

    @Bean
    @ConditionalOnMissingBean
    public RunnerController runnerController(RunnerService service) {
        return new RunnerController(service);
    }
}
