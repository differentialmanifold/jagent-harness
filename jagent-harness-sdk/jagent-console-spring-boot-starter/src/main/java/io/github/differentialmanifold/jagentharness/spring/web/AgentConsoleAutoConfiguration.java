package io.github.differentialmanifold.jagentharness.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptProvider;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillRegistry;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.tool.KnowledgeFileToolConfiguration;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContextFactory;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsageStore;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpConfigurationManager;
import io.github.differentialmanifold.jagentharness.mcp.spring.McpRuntime;
import io.github.differentialmanifold.jagentharness.spring.HarnessProperties;
import io.github.differentialmanifold.jagentharness.spring.ModelAccessTokenProvider;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.DispatcherServlet;

@org.springframework.boot.autoconfigure.AutoConfiguration(
        afterName = {
            "io.github.differentialmanifold.jagentharness.spring.AgentHarnessAutoConfiguration",
            "io.github.differentialmanifold.jagentharness.mcp.spring.McpAutoConfiguration"
        })
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "agent",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnClass(DispatcherServlet.class)
@EnableConfigurationProperties(ConsoleProperties.class)
@Import(WebConfiguration.class)
public class AgentConsoleAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "chatRequestBodyLimitFilterRegistration")
    public FilterRegistrationBean<ChatRequestBodyLimitFilter>
            chatRequestBodyLimitFilterRegistration(
                    ConsoleProperties properties, ObjectMapper objectMapper) {
        ChatRequestBodyLimitFilter filter =
                new ChatRequestBodyLimitFilter(
                        properties.getMaxChatRequestBodySize().toBytes(), objectMapper);
        FilterRegistrationBean<ChatRequestBodyLimitFilter> registration =
                new FilterRegistrationBean<ChatRequestBodyLimitFilter>(filter);
        registration.setName("chatRequestBodyLimitFilter");
        registration.addUrlPatterns("/api/v1/chat", "/api/v1/runs/*");
        registration.setDispatcherTypes(EnumSet.of(DispatcherType.REQUEST));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkspaceRootResolver workspaceRootResolver() {
        return new DefaultWorkspaceRootResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentContextController agentContextController(
            ToolRegistry toolRegistry,
            SkillRegistry skillRegistry,
            AgentSettings settings,
            SessionManager sessionManager,
            WorkspaceRootResolver workspaceRootResolver,
            ObjectProvider<KnowledgeFileStore> knowledgeFileStore) {
        return new AgentContextController(
                toolRegistry,
                skillRegistry,
                settings,
                sessionManager,
                workspaceRootResolver,
                knowledgeFileStore.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public PromptPreviewController promptPreviewController(
            PromptProvider promptProvider,
            ToolRegistry toolRegistry,
            AgentSettings settings,
            SessionManager sessionManager,
            WorkspaceRootResolver workspaceRootResolver) {
        return new PromptPreviewController(
                promptProvider, toolRegistry, settings, sessionManager, workspaceRootResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public HealthController healthController(HarnessProperties properties) {
        return new HealthController(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderController providerController(
            ModelProviderRegistry providerRegistry,
            HarnessProperties properties,
            ModelAccessTokenProvider accessTokenProvider) {
        return new ProviderController(providerRegistry, properties, accessTokenProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionController sessionController(
            SessionManager sessionManager,
            WorkspaceRootResolver workspaceRootResolver,
            ObjectProvider<ModelCallUsageStore> modelCallUsageStore) {
        return new SessionController(
                sessionManager, workspaceRootResolver, modelCallUsageStore.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolController toolController(
            ToolRegistry toolRegistry,
            ObjectProvider<KnowledgeFileToolConfiguration> toolConfiguration,
            SessionManager sessionManager,
            ToolContextFactory toolContextFactory,
            ObjectMapper objectMapper) {
        return new ToolController(
                toolRegistry,
                toolConfiguration.getIfAvailable(),
                sessionManager,
                toolContextFactory,
                objectMapper);
    }

    @Bean
    @ConditionalOnBean(KnowledgeFileStore.class)
    @ConditionalOnMissingBean
    public VirtualFileController virtualFileController(
            KnowledgeFileStore knowledgeFileStore, SessionManager sessionManager) {
        return new VirtualFileController(knowledgeFileStore, sessionManager);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.github.differentialmanifold.jagentharness.mcp.spring.McpRuntime")
    static class McpEndpoints {
        @Bean
        @ConditionalOnBean({McpConfigurationManager.class, McpRuntime.class})
        @ConditionalOnMissingBean
        public McpController mcpController(
                McpConfigurationManager configurationManager,
                McpRuntime runtime,
                SessionManager sessionManager) {
            return new McpController(configurationManager, runtime, sessionManager);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiExceptionHandler agentConsoleExceptionHandler() {
        return new ApiExceptionHandler();
    }
}
