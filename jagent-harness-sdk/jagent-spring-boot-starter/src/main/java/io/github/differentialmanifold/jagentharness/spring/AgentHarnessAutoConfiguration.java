package io.github.differentialmanifold.jagentharness.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentRunner;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.conversation.CompactionStore;
import io.github.differentialmanifold.jagentharness.core.conversation.ConversationContextManager;
import io.github.differentialmanifold.jagentharness.core.conversation.DefaultConversationContextManager;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventListener;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.event.DefaultAgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.message.MessageRepository;
import io.github.differentialmanifold.jagentharness.core.model.InMemoryModelCatalogStore;
import io.github.differentialmanifold.jagentharness.core.model.ModelCatalog;
import io.github.differentialmanifold.jagentharness.core.model.ModelCatalogStore;
import io.github.differentialmanifold.jagentharness.core.model.ModelProfile;
import io.github.differentialmanifold.jagentharness.core.prompt.DatabaseSkillProvider;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptProvider;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptService;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillManifestStore;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillProvider;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillRegistry;
import io.github.differentialmanifold.jagentharness.core.prompt.SystemPromptContributor;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.core.provider.http.ModelHttpClient;
import io.github.differentialmanifold.jagentharness.core.provider.openai.OpenAiCompatibleProvider;
import io.github.differentialmanifold.jagentharness.core.provider.openai.OpenAiCompatibleProviderConfig;
import io.github.differentialmanifold.jagentharness.core.session.DefaultSessionManager;
import io.github.differentialmanifold.jagentharness.core.session.DefaultSessionStore;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRepository;
import io.github.differentialmanifold.jagentharness.core.session.SessionStore;
import io.github.differentialmanifold.jagentharness.core.skill.*;
import io.github.differentialmanifold.jagentharness.core.support.PathsSupport;
import io.github.differentialmanifold.jagentharness.core.timeline.TimelineEventRecorder;
import io.github.differentialmanifold.jagentharness.core.timeline.TimelineEventRepository;
import io.github.differentialmanifold.jagentharness.core.tool.DefaultToolContextFactory;
import io.github.differentialmanifold.jagentharness.core.tool.KnowledgeFileToolConfiguration;
import io.github.differentialmanifold.jagentharness.core.tool.ToolAvailabilityPolicy;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContextFactory;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolProvider;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import io.github.differentialmanifold.jagentharness.core.tool.builtin.SkillTool;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsageStore;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@org.springframework.boot.autoconfigure.AutoConfiguration(
        afterName = {
            "io.github.differentialmanifold.jagentharness.store.jdbc.JdbcStoreAutoConfiguration",
            "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration"
        })
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "harness",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(HarnessProperties.class)
@org.springframework.context.annotation.Import(ClientToolsConfiguration.class)
public class AgentHarnessAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ModelCatalogStore.class)
    public ModelCatalogStore inMemoryModelCatalogStore() {
        return new InMemoryModelCatalogStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelCatalog modelCatalog(
            ModelCatalogStore store,
            HarnessProperties properties,
            ModelProviderRegistry providers,
            ObjectMapper json) {
        ModelProfile initial = new ModelProfile();
        initial.id = "default";
        initial.name = properties.getModel().getModel();
        initial.model = properties.getModel().getModel();
        initial.provider = properties.getModel().getProvider();
        initial.baseUrl =
                properties.getModel().getBaseUrl() == null
                        ? ""
                        : properties.getModel().getBaseUrl();
        initial.apiKey =
                properties.getModel().getApiKey() == null ? "" : properties.getModel().getApiKey();
        initial.temperature = properties.getModel().getTemperature();
        initial.timeoutSeconds = properties.getModel().getTimeoutSeconds();
        initial.streamEnabled = properties.getModel().isStreamEnabled();
        initial.includeUsage = properties.getModel().isIncludeUsage();
        initial.contextWindowTokens = properties.getModel().getContextWindowTokens();
        initial.description = "";
        initial.credentialsConfigured =
                properties.getModel().getApiKey() != null
                        && !properties.getModel().getApiKey().isEmpty();
        java.util.Map<String, ModelProvider> cache = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Map<String, String> versions = new java.util.concurrent.ConcurrentHashMap<>();
        return new ModelCatalog(
                store,
                initial,
                profile -> {
                    if (profile.baseUrl.isEmpty()
                            || (profile.provider.equals(initial.provider)
                                    && profile.baseUrl.equals(initial.baseUrl)
                                    && profile.apiKey.equals(initial.apiKey)
                                    && profile.timeoutSeconds == initial.timeoutSeconds
                                    && profile.streamEnabled == initial.streamEnabled
                                    && profile.includeUsage == initial.includeUsage))
                        return providers.get(profile.provider);
                    String version =
                            profile.baseUrl
                                    + "\n"
                                    + profile.apiKey
                                    + "\n"
                                    + profile.timeoutSeconds
                                    + "\n"
                                    + profile.streamEnabled
                                    + "\n"
                                    + profile.includeUsage;
                    synchronized (cache) {
                        if (!version.equals(versions.get(profile.id))) {
                            OpenAiCompatibleProviderConfig config =
                                    new OpenAiCompatibleProviderConfig();
                            config.setBaseUrl(profile.baseUrl);
                            config.setApiKey(profile.apiKey);
                            config.setTimeoutSeconds(profile.timeoutSeconds);
                            config.setStreamEnabled(profile.streamEnabled);
                            config.setIncludeUsage(profile.includeUsage);
                            cache.put(profile.id, new OpenAiCompatibleProvider(config, json));
                            versions.put(profile.id, version);
                        }
                        return cache.get(profile.id);
                    }
                });
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentSettings agentSettings(HarnessProperties properties) {
        AgentSettings settings = new AgentSettings();
        settings.setProvider(properties.getModel().getProvider());
        settings.setModel(properties.getModel().getModel());
        settings.setTemperature(properties.getModel().getTemperature());
        settings.setConfigRoot(PathsSupport.expandUserHome(properties.getPrompt().getConfigRoot()));
        settings.setCompactionEnabled(properties.getCompaction().isEnabled());
        settings.setContextWindowTokens(properties.getModel().getContextWindowTokens());
        settings.setCompactionThresholdRatio(properties.getCompaction().getThresholdRatio());
        settings.setCompactionRecentMessages(properties.getCompaction().getRecentMessages());
        settings.setCompactionTargetTokens(properties.getCompaction().getTargetTokens());
        settings.setModelRetryEnabled(properties.getModel().getRetry().isEnabled());
        settings.setModelRetryMaxAttempts(properties.getModel().getRetry().getMaxAttempts());
        settings.setModelRetryInitialDelayMillis(
                properties.getModel().getRetry().getInitialDelayMillis());
        settings.setModelRetryMaxDelayMillis(properties.getModel().getRetry().getMaxDelayMillis());
        return settings;
    }

    @Bean
    @ConditionalOnMissingBean(SkillActivationStore.class)
    public SkillActivationStore skillActivationStore() {
        return new InMemorySkillActivationStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillActivationService skillActivationService(
            SkillActivationStore store, ObjectProvider<SkillToolResolver> resolver) {
        return new SkillActivationService(store, resolver::getIfAvailable);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillTool skillTool(
            ObjectMapper objectMapper,
            ObjectProvider<KnowledgeFileStore> knowledgeFileStore,
            SkillActivationService activationService,
            SkillRegistry skillRegistry) {
        return new SkillTool(
                objectMapper,
                knowledgeFileStore.getIfAvailable(),
                activationService,
                skillRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolRegistry toolRegistry(
            List<ToolDefinition> tools,
            ObjectProvider<ToolProvider> toolProviders,
            ObjectProvider<ToolAvailabilityPolicy> toolAvailabilityPolicies) {
        List<ToolProvider> providers = new ArrayList<ToolProvider>();
        toolProviders.forEach(providers::add);
        List<ToolAvailabilityPolicy> policies = new ArrayList<ToolAvailabilityPolicy>();
        toolAvailabilityPolicies.forEach(policies::add);
        return new ToolRegistry(tools, providers, policies);
    }

    @Bean
    @ConditionalOnMissingBean
    public KnowledgeFileToolConfiguration knowledgeFileToolConfiguration(
            KnowledgeFileStore knowledgeFileStore, ObjectMapper objectMapper) {
        return new KnowledgeFileToolConfiguration(knowledgeFileStore, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(name = "openAiCompatibleProvider")
    public ModelProvider openAiCompatibleProvider(
            HarnessProperties properties,
            ObjectMapper objectMapper,
            ObjectProvider<ModelHttpClient> httpClient,
            ModelAccessTokenProvider accessTokenProvider) {
        OpenAiCompatibleProviderConfig config = new OpenAiCompatibleProviderConfig();
        config.setBaseUrl(properties.getModel().getBaseUrl());
        config.setApiKey(properties.getModel().getApiKey());
        config.setTimeoutSeconds(properties.getModel().getTimeoutSeconds());
        config.setStreamEnabled(properties.getModel().isStreamEnabled());
        config.setIncludeUsage(properties.getModel().isIncludeUsage());
        ModelHttpClient effectiveHttpClient = httpClient.getIfAvailable();
        if (effectiveHttpClient != null) {
            return new OpenAiCompatibleProvider(
                    config, objectMapper, effectiveHttpClient, accessTokenProvider::getAccessToken);
        }
        return new OpenAiCompatibleProvider(
                config, objectMapper, accessTokenProvider::getAccessToken);
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelAccessTokenProvider modelAccessTokenProvider(HarnessProperties properties) {
        return () -> properties.getModel().getApiKey();
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelProviderRegistry modelProviderRegistry(List<ModelProvider> providers) {
        return new ModelProviderRegistry(providers);
    }

    @Bean
    @ConditionalOnMissingBean
    public DatabaseSkillProvider databaseSkillProvider(SkillManifestStore skillManifestStore) {
        return new DatabaseSkillProvider(skillManifestStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry skillRegistry(List<SkillProvider> providers) {
        return new SkillRegistry(providers);
    }

    @Bean
    @ConditionalOnMissingBean
    public PromptProvider promptProvider(
            HarnessProperties properties,
            SkillRegistry skillRegistry,
            ObjectProvider<KnowledgeFileStore> knowledgeFileStore,
            ObjectProvider<SystemPromptContributor> systemPromptContributors,
            SkillActivationStore activationStore) {
        List<SystemPromptContributor> contributors = new ArrayList<SystemPromptContributor>();
        systemPromptContributors.forEach(contributors::add);
        return new PromptService(
                skillRegistry,
                PathsSupport.expandUserHome(properties.getPrompt().getConfigRoot()),
                knowledgeFileStore.getIfAvailable(),
                contributors,
                activationStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolContextFactory toolContextFactory(AgentSettings settings) {
        return new DefaultToolContextFactory(settings);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentEventPublisher agentEventPublisher(
            ObjectMapper objectMapper, ObjectProvider<AgentEventListener> listeners) {
        return new DefaultAgentEventPublisher(
                objectMapper,
                () -> {
                    List<AgentEventListener> result = new ArrayList<AgentEventListener>();
                    listeners.forEach(result::add);
                    return result;
                });
    }

    @Bean
    @ConditionalOnMissingBean
    public TimelineEventRecorder timelineEventRecorder(TimelineEventRepository timelineEventStore) {
        return new TimelineEventRecorder(timelineEventStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionStore sessionStore(
            SessionRepository sessionRepository, MessageRepository messageRepository) {
        return new DefaultSessionStore(sessionRepository, messageRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionManager sessionManager(
            SessionRepository sessionRepository,
            ObjectProvider<TimelineEventRepository> timelineEventStore) {
        return new DefaultSessionManager(sessionRepository, timelineEventStore.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationContextManager conversationContextManager(
            AgentSettings settings,
            CompactionStore compactionStore,
            AgentEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            ModelCallUsageStore modelCallUsageStore) {
        return new DefaultConversationContextManager(
                settings, compactionStore, eventPublisher, objectMapper, modelCallUsageStore);
    }

    @Bean
    @ConditionalOnMissingBean(
            io.github.differentialmanifold.jagentharness.core.agent.AgentHarness.class)
    public AgentRunner agentRunner(
            AgentSettings settings,
            SessionStore sessionStore,
            AgentEventPublisher eventPublisher,
            PromptProvider promptProvider,
            ToolRegistry toolRegistry,
            ModelProviderRegistry providerRegistry,
            ObjectProvider<ToolContextFactory> toolContextFactory,
            ConversationContextManager conversationContextManager,
            ModelCallUsageStore modelCallUsageStore,
            ObjectMapper objectMapper,
            ModelCatalog modelCatalog) {
        ToolContextFactory effectiveToolContextFactory =
                toolContextFactory.getIfAvailable(DefaultToolContextFactory::new);
        return new AgentRunner(
                        settings,
                        sessionStore,
                        eventPublisher,
                        promptProvider,
                        toolRegistry,
                        providerRegistry,
                        effectiveToolContextFactory,
                        conversationContextManager,
                        modelCallUsageStore,
                        objectMapper)
                .withModelCatalog(modelCatalog);
    }
}
