package io.github.differentialmanifold.jagentharness.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.core.timeline.TimelineEventRepository;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = AgentConsoleAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(
        prefix = "agent",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ChatAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public ChatService chatService(
            ObjectMapper json,
            AgentHarness runner,
            SessionStore sessions,
            SessionRepository records,
            TimelineEventRepository timeline,
            ToolRegistry tools,
            RunInputCoordinator inputs,
            AgentEventPublisher publisher) {
        return new ChatService(json, runner, sessions, records, timeline, tools, inputs, publisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatController chatController(ChatService service, RunInputCoordinator inputs) {
        return new ChatController(service, inputs);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiAuthenticationFilter apiAuthenticationFilter(
            @Value("${agent.protocol.token:}") String token,
            @Value("${server.address:0.0.0.0}") String bind) {
        return new ApiAuthenticationFilter(token, bind);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProtocolErrors protocolErrors() {
        return new ProtocolErrors();
    }
}
