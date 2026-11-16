package io.github.differentialmanifold.jagentharness.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.session.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
        after = {
            AgentConsoleAutoConfiguration.class,
            io.github.differentialmanifold.jagentharness.spring.ExecutionAutoConfiguration.class
        })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
        prefix = "harness",
        name = {"enabled", "console.enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class ChatAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public SessionEventStream sessionEventStream(SessionManager sessions, ObjectMapper json) {
        return new SessionEventStream(sessions, json);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatController chatController(
            io.github.differentialmanifold.jagentharness.core.execution.RunService service) {
        return new ChatController(service);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiAuthenticationFilter apiAuthenticationFilter(
            @Value("${harness.protocol.token:}") String token,
            @Value("${server.address:0.0.0.0}") String bind,
            @Value("${harness.identity.enabled:false}") boolean identities,
            org.springframework.beans.factory.ObjectProvider<UserAccessService> access) {
        return identities
                ? new ApiAuthenticationFilter(access.getObject())
                : new ApiAuthenticationFilter(token, bind);
    }

    @Bean
    @ConditionalOnProperty(prefix = "harness.identity", name = "enabled", havingValue = "true")
    public UserAccessService userAccessService(
            io.github.differentialmanifold.jagentharness.core.identity.UserStore users,
            io.github.differentialmanifold.jagentharness.core.runner.RunnerService runners,
            io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentService agents,
            @Value("${harness.identity.local-token-file:}") String file)
            throws java.io.IOException {
        if (!file.trim().isEmpty())
            io.github.differentialmanifold.jagentharness.core.identity.UserTokenFiles.bootstrap(
                    users, java.nio.file.Paths.get(file));
        return new UserAccessService(users, runners, agents);
    }

    @Bean
    @ConditionalOnProperty(prefix = "harness.identity", name = "enabled", havingValue = "true")
    public UserAccessController userAccessController(UserAccessService service) {
        return new UserAccessController(service);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProtocolErrors protocolErrors() {
        return new ProtocolErrors();
    }
}
