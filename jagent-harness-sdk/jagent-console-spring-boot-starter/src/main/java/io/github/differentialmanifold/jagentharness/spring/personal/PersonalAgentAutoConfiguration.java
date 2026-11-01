package io.github.differentialmanifold.jagentharness.spring.personal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentHarness;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.message.MessageRepository;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.core.session.*;
import io.github.differentialmanifold.jagentharness.spring.web.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
        after = {ChatAutoConfiguration.class, RunnerAutoConfiguration.class},
        afterName =
                "io.github.differentialmanifold.jagentharness.store.jdbc.JdbcStoreAutoConfiguration")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean({PersonalAgentStore.class, ChatService.class})
@ConditionalOnProperty(prefix = "harness.personal", name = "enabled", matchIfMissing = true)
public class PersonalAgentAutoConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    public PersonalAgentService personalAgentService(
            PersonalAgentStore store,
            SessionRepository records,
            SessionManager sessions,
            SessionStore history,
            AgentHarness harness,
            ChatService chat,
            ObjectProvider<RunnerService> runners,
            PersonalAgentKnowledge knowledge,
            PersonalAgentLearning learning) {
        return new PersonalAgentService(
                store,
                records,
                sessions,
                history,
                harness,
                chat,
                runners.getIfAvailable(),
                knowledge,
                learning);
    }

    @Bean
    @ConditionalOnMissingBean
    public PersonalAgentTools personalAgentTools(
            ObjectProvider<PersonalAgentService> services,
            PersonalAgentStore store,
            ObjectMapper json,
            PersonalAgentKnowledge knowledge) {
        return new PersonalAgentTools(services, store, json, knowledge);
    }

    @Bean
    @ConditionalOnMissingBean
    public PersonalAgentController personalAgentController(
            PersonalAgentService service,
            PersonalAgentStore store,
            PersonalAgentKnowledge knowledge,
            PersonalAgentLearning learning) {
        return new PersonalAgentController(service, store, knowledge, learning);
    }

    @Bean
    @ConditionalOnMissingBean
    public PersonalAgentKnowledge personalAgentKnowledge(
            PersonalAgentStore store,
            KnowledgeFileStore files,
            MessageRepository messages,
            SessionRepository sessions) {
        return new PersonalAgentKnowledge(store, files, messages, sessions);
    }

    @Bean
    @ConditionalOnMissingBean
    public PersonalAgentLearning personalAgentLearning(
            PersonalAgentStore store,
            PersonalAgentKnowledge knowledge,
            SessionStore history,
            KnowledgeFileStore files,
            ModelProviderRegistry providers,
            AgentSettings settings,
            ObjectMapper json,
            io.github.differentialmanifold.jagentharness.core.conversation.CompactionStore
                    compactions,
            @Value("${harness.personal.learning.enabled:true}") boolean enabled,
            @Value("${harness.personal.learning.timeout-ms:30000}") long timeout) {
        return new PersonalAgentLearning(
                store,
                knowledge,
                history,
                files,
                providers,
                settings,
                json,
                enabled,
                timeout,
                compactions);
    }
}
