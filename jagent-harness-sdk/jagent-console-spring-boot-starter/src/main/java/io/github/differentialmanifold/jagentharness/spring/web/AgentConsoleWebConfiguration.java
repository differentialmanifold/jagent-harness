package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.agent.AgentHarness;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopCoordinator;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillRegistry;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import io.github.differentialmanifold.jagentharness.spring.HarnessProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.servlet.DispatcherServlet;

@Configuration
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "harness.console", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ConsoleProperties.class)
@Import({AgentExecutionConfiguration.class, WebConfiguration.class})
public class AgentConsoleWebConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WorkspaceRootResolver workspaceRootResolver() {
        return new DefaultWorkspaceRootResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentContextController agentContextController(ToolRegistry toolRegistry,
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
    public PromptPreviewController promptPreviewController(PromptProvider promptProvider,
                                                           ToolRegistry toolRegistry,
                                                           AgentSettings settings,
                                                           SessionManager sessionManager,
                                                           WorkspaceRootResolver workspaceRootResolver) {
        return new PromptPreviewController(
                promptProvider,
                toolRegistry,
                settings,
                sessionManager,
                workspaceRootResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public ChatController chatController(SessionManager sessionManager,
                                         AgentHarness agentHarness,
                                         TaskExecutor agentTaskExecutor,
                                         RunStopCoordinator runStopCoordinator) {
        return new ChatController(sessionManager, agentHarness, agentTaskExecutor, runStopCoordinator);
    }

    @Bean
    @ConditionalOnMissingBean
    public HealthController healthController(HarnessProperties properties) {
        return new HealthController(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public ProviderController providerController(ModelProviderRegistry providerRegistry,
                                                 HarnessProperties properties) {
        return new ProviderController(providerRegistry, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionController sessionController(SessionManager sessionManager,
                                               WorkspaceRootResolver workspaceRootResolver) {
        return new SessionController(sessionManager, workspaceRootResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolController toolController(ToolRegistry toolRegistry) {
        return new ToolController(toolRegistry);
    }

    @Bean
    @ConditionalOnBean(KnowledgeFileStore.class)
    @ConditionalOnMissingBean
    public VirtualFileController virtualFileController(KnowledgeFileStore knowledgeFileStore) {
        return new VirtualFileController(knowledgeFileStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentConsoleExceptionHandler agentConsoleExceptionHandler() {
        return new AgentConsoleExceptionHandler();
    }
}
