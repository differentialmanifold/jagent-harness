package io.github.differentialmanifold.jagentharness.spring;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentRunner;
import io.github.differentialmanifold.jagentharness.core.conversation.CompactionStore;
import io.github.differentialmanifold.jagentharness.core.conversation.ConversationContextManager;
import io.github.differentialmanifold.jagentharness.core.conversation.DefaultConversationContextManager;
import io.github.differentialmanifold.jagentharness.core.conversation.NoopCompactionStore;
import io.github.differentialmanifold.jagentharness.core.session.DefaultSessionManager;
import io.github.differentialmanifold.jagentharness.core.session.DefaultSessionStore;
import io.github.differentialmanifold.jagentharness.core.message.MessageRepository;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRepository;
import io.github.differentialmanifold.jagentharness.core.session.SessionStore;
import io.github.differentialmanifold.jagentharness.core.timeline.TimelineEventRecorder;
import io.github.differentialmanifold.jagentharness.core.timeline.TimelineEventRepository;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventListener;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.event.DefaultAgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.prompt.FileSkillProvider;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptProvider;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptService;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillProvider;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillRegistry;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpClient;
import io.github.differentialmanifold.jagentharness.core.provider.openai.OpenAiCompatibleProvider;
import io.github.differentialmanifold.jagentharness.core.provider.openai.OpenAiCompatibleProviderConfig;
import io.github.differentialmanifold.jagentharness.core.agent.AgentHarness;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.tool.DefaultToolContextFactory;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContextFactory;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import io.github.differentialmanifold.jagentharness.core.tool.builtin.ReadTool;
import io.github.differentialmanifold.jagentharness.core.support.PathsSupport;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@AutoConfigureAfter(name = "io.github.differentialmanifold.jagentharness.store.jdbc.JdbcStoreAutoConfiguration")
@EnableConfigurationProperties(HarnessProperties.class)
public class AgentHarnessAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AgentSettings agentSettings(HarnessProperties properties) {
        AgentSettings settings = new AgentSettings();
        settings.setProvider(properties.getModel().getProvider());
        settings.setModel(properties.getModel().getModel());
        settings.setTemperature(properties.getModel().getTemperature());
        settings.setConfigRoot(PathsSupport.expandUserHome(properties.getPrompt().getConfigRoot()));
        settings.setCompactionEnabled(properties.getCompaction().isEnabled());
        settings.setContextWindowTokens(properties.getCompaction().getContextWindowTokens());
        settings.setCompactionThresholdRatio(properties.getCompaction().getThresholdRatio());
        settings.setCompactionRecentMessages(properties.getCompaction().getRecentMessages());
        settings.setCompactionTargetTokens(properties.getCompaction().getTargetTokens());
        return settings;
    }

    @Bean
    @ConditionalOnMissingBean(name = "readTool")
    public ReadTool readTool(ObjectMapper objectMapper) {
        return new ReadTool(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(List<ToolDefinition> tools) {
        return new ToolRegistry(tools);
    }

    @Bean
    @ConditionalOnMissingBean(name = "openAiCompatibleProvider")
    public ModelProvider openAiCompatibleProvider(HarnessProperties properties,
                                                  ObjectMapper objectMapper,
                                                  ObjectProvider<ModelHttpClient> httpClient) {
        OpenAiCompatibleProviderConfig config = new OpenAiCompatibleProviderConfig();
        config.setBaseUrl(properties.getModel().getBaseUrl());
        config.setApiKey(properties.getModel().getApiKey());
        config.setTimeoutSeconds(properties.getModel().getTimeoutSeconds());
        config.setStreamEnabled(properties.getModel().isStreamEnabled());
        ModelHttpClient effectiveHttpClient = httpClient.getIfAvailable();
        if (effectiveHttpClient != null) {
            return new OpenAiCompatibleProvider(config, objectMapper, effectiveHttpClient);
        }
        return new OpenAiCompatibleProvider(config, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelProviderRegistry modelProviderRegistry(List<ModelProvider> providers) {
        return new ModelProviderRegistry(providers);
    }

    @Bean
    @ConditionalOnMissingBean(FileSkillProvider.class)
    public FileSkillProvider fileSkillProvider(HarnessProperties properties) {
        return new FileSkillProvider(
                PathsSupport.expandUserHome(properties.getPrompt().getConfigRoot()),
                properties.getPrompt().getSkillsDir());
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry skillRegistry(List<SkillProvider> providers) {
        return new SkillRegistry(providers);
    }

    @Bean
    @ConditionalOnMissingBean
    public PromptProvider promptProvider(HarnessProperties properties, SkillRegistry skillRegistry) {
        return new PromptService(
                skillRegistry,
                PathsSupport.expandUserHome(properties.getPrompt().getConfigRoot()));
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolContextFactory toolContextFactory(AgentSettings settings) {
        return new DefaultToolContextFactory(settings);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentEventPublisher agentEventPublisher(ObjectMapper objectMapper,
                                                   ObjectProvider<AgentEventListener> listeners) {
        return new DefaultAgentEventPublisher(objectMapper, () -> {
            List<AgentEventListener> result = new ArrayList<AgentEventListener>();
            listeners.forEach(result::add);
            return result;
        });
    }

    @Bean
    @ConditionalOnBean(TimelineEventRepository.class)
    @ConditionalOnMissingBean
    public TimelineEventRecorder timelineEventRecorder(TimelineEventRepository timelineEventStore) {
        return new TimelineEventRecorder(timelineEventStore);
    }

    @Bean
    @ConditionalOnBean({SessionRepository.class, MessageRepository.class})
    @ConditionalOnMissingBean(SessionStore.class)
    public SessionStore sessionStore(SessionRepository sessionRepository,
                                     MessageRepository messageRepository) {
        return new DefaultSessionStore(sessionRepository, messageRepository);
    }

    @Bean
    @ConditionalOnBean(SessionRepository.class)
    @ConditionalOnMissingBean(SessionManager.class)
    public SessionManager sessionManager(SessionRepository sessionRepository,
                                         ObjectProvider<TimelineEventRepository> timelineEventStore) {
        return new DefaultSessionManager(sessionRepository, timelineEventStore.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public CompactionStore compactionStore() {
        return new NoopCompactionStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationContextManager conversationContextManager(AgentSettings settings,
                                                                 CompactionStore compactionStore,
                                                                 AgentEventPublisher eventPublisher,
                                                                 ObjectMapper objectMapper) {
        return new DefaultConversationContextManager(settings, compactionStore, eventPublisher, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentHarness agentHarness(AgentSettings settings,
                                     SessionStore sessionStore,
                                     AgentEventPublisher eventPublisher,
                                     PromptProvider promptProvider,
                                     ToolRegistry toolRegistry,
                                     ModelProviderRegistry providerRegistry,
                                     ObjectProvider<ToolContextFactory> toolContextFactory,
                                     ConversationContextManager conversationContextManager,
                                     ObjectMapper objectMapper) {
        ToolContextFactory effectiveToolContextFactory = toolContextFactory.getIfAvailable(DefaultToolContextFactory::new);
        return new AgentRunner(
                settings,
                sessionStore,
                eventPublisher,
                promptProvider,
                toolRegistry,
                providerRegistry,
                effectiveToolContextFactory,
                conversationContextManager,
                objectMapper);
    }
}
