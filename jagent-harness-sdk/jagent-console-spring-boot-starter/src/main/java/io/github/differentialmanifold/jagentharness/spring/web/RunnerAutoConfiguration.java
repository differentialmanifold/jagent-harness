package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.runner.RunnerService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
        after = ChatAutoConfiguration.class,
        afterName =
                "io.github.differentialmanifold.jagentharness.store.jdbc.JdbcStoreAutoConfiguration")
@ConditionalOnBean(RunnerService.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
        prefix = "harness",
        name = {"enabled", "console.enabled", "runner.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class RunnerAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public RunnerController runnerController(
            RunnerService service,
            org.springframework.beans.factory.ObjectProvider<
                            io.github.differentialmanifold.jagentharness.core.personal
                                    .PersonalAgentService>
                    personal) {
        return new RunnerController(service)
                .onRegistered(
                        info -> {
                            io.github.differentialmanifold.jagentharness.core.personal
                                            .PersonalAgentService
                                    agents = personal.getIfAvailable();
                            if (agents != null) agents.bindMainbotIfUnbound(info.runnerId);
                        });
    }
}
