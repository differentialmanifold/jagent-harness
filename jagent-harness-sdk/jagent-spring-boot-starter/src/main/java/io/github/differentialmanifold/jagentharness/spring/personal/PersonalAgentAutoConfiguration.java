package io.github.differentialmanifold.jagentharness.spring.personal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentHarness;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.execution.RunService;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.message.MessageRepository;
import io.github.differentialmanifold.jagentharness.core.model.ModelCatalog;
import io.github.differentialmanifold.jagentharness.core.personal.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore;
import io.github.differentialmanifold.jagentharness.core.project.ProjectService;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.core.session.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
        afterName = {
            "io.github.differentialmanifold.jagentharness.spring.AgentHarnessAutoConfiguration",
            "io.github.differentialmanifold.jagentharness.spring.ExecutionAutoConfiguration",
            "io.github.differentialmanifold.jagentharness.store.jdbc.JdbcStoreAutoConfiguration"
        })
@ConditionalOnBean({
    AgentHarness.class,
    PersonalAgentStore.class,
    ProjectService.class,
    RunService.class
})
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
            ProjectService projects,
            RunService runs,
            PersonalAgentKnowledge knowledge,
            PersonalAgentLearning learning,
            ObjectProvider<ModelCatalog> models,
            ObjectProvider<io.github.differentialmanifold.jagentharness.core.identity.UserDirectory>
                    users) {
        return new PersonalAgentService(
                        store, records, sessions, history, harness, projects, runs, knowledge,
                        learning)
                .withModels(models.getIfAvailable())
                .withUsers(
                        users.getIfAvailable(
                                () ->
                                        io.github.differentialmanifold.jagentharness.core.identity
                                                .UserDirectory.LOCAL));
    }

    @Bean
    @ConditionalOnMissingBean
    public PersonalAgentTools personalAgentTools(
            ObjectProvider<PersonalAgentService> services,
            PersonalAgentStore store,
            ObjectMapper json,
            PersonalAgentKnowledge knowledge,
            HarnessOperations management) {
        return new PersonalAgentTools(services::getObject, store, json, knowledge)
                .withManagement(management);
    }

    @Bean
    @ConditionalOnMissingBean
    public HarnessOperations harnessOperations(
            ObjectProvider<ModelCatalog> models,
            ObjectProvider<PersonalAgentService> agents,
            ObjectProvider<PersonalAgentStore> stores,
            ObjectProvider<PersonalAgentKnowledge> knowledge,
            ObjectProvider<PersonalAgentTools> tools,
            ObjectProvider<ProjectService> projects,
            ObjectProvider<io.github.differentialmanifold.jagentharness.core.runner.RunnerService>
                    runners,
            ObjectProvider<KnowledgeFileStore> files,
            ObjectProvider<SessionManager> sessions,
            ObjectMapper json) {
        HarnessOperations operations = new HarnessOperations();
        ModelManagement.register(operations, models::getObject, json);
        AgentManagement.register(
                operations,
                agents::getObject,
                stores::getObject,
                knowledge::getObject,
                tools::getObject,
                json);
        ProjectManagement.register(
                operations, projects::getObject, runners::getObject, agents::getObject, json);
        KnowledgeManagement.register(operations, files::getObject, sessions::getObject);
        return operations;
    }

    @Bean
    @ConditionalOnMissingBean
    public PersonalAgentSkills personalAgentSkills(PersonalAgentStore store) {
        return new PersonalAgentSkills(store);
    }

    @Bean
    @ConditionalOnMissingBean
    public PersonalAgentKnowledge personalAgentKnowledge(
            PersonalAgentStore store,
            KnowledgeFileStore files,
            MessageRepository messages,
            SessionRepository sessions,
            PersonalAgentSkills skills) {
        return new PersonalAgentKnowledge(store, files, messages, sessions, skills);
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
            org.springframework.beans.factory.ObjectProvider<ModelCatalog> models,
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
                        compactions)
                .withModels(models.getIfAvailable());
    }
}
