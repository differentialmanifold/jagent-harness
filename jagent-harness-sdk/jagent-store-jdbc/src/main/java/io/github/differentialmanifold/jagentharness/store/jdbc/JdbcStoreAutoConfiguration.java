package io.github.differentialmanifold.jagentharness.store.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.*;
import io.github.differentialmanifold.jagentharness.core.conversation.CompactionStore;
import io.github.differentialmanifold.jagentharness.core.fs.*;
import io.github.differentialmanifold.jagentharness.core.message.MessageRepository;
import io.github.differentialmanifold.jagentharness.core.prompt.*;
import io.github.differentialmanifold.jagentharness.core.session.SessionRepository;
import io.github.differentialmanifold.jagentharness.core.timeline.TimelineEventRepository;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalCoordinator;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsageStore;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

@org.springframework.boot.autoconfigure.AutoConfiguration(
        afterName = {
            "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
            "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration"
        })
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "agent",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@ConditionalOnClass(JdbcTemplate.class)
@EnableConfigurationProperties({
    JdbcStoreProperties.class,
    JdbcRunStopProperties.class,
    JdbcToolApprovalProperties.class
})
public class JdbcStoreAutoConfiguration {

    @Bean(initMethod = "initialize")
    @ConditionalOnMissingBean
    @DependsOnDatabaseInitialization
    public JdbcSchemaInitializer jdbcSchemaInitializer(
            DataSource dataSource, JdbcStoreProperties properties, ResourceLoader resources) {
        return new JdbcSchemaInitializer(dataSource, properties, resources);
    }

    @Bean
    @ConditionalOnMissingBean(SessionRepository.class)
    public JdbcSessionRepository jdbcSessionRepository(
            JdbcTemplate jdbcTemplate,
            JdbcSchemaInitializer schemaInitializer,
            JdbcStoreProperties properties) {
        return new JdbcSessionRepository(jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean(MessageRepository.class)
    public JdbcMessageRepository jdbcMessageRepository(
            JdbcTemplate jdbcTemplate,
            JdbcSchemaInitializer schemaInitializer,
            ObjectMapper objectMapper,
            JdbcStoreProperties properties) {
        return new JdbcMessageRepository(jdbcTemplate, objectMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean(TimelineEventRepository.class)
    public JdbcTimelineEventRepository jdbcTimelineEventRepository(
            JdbcTemplate jdbcTemplate,
            JdbcSchemaInitializer schemaInitializer,
            JdbcStoreProperties properties) {
        return new JdbcTimelineEventRepository(jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean(CompactionStore.class)
    public JdbcCompactionStore jdbcCompactionStore(
            JdbcTemplate jdbcTemplate,
            JdbcSchemaInitializer schemaInitializer,
            JdbcStoreProperties properties) {
        return new JdbcCompactionStore(jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean(ModelCallUsageStore.class)
    public JdbcModelCallUsageStore jdbcModelCallUsageStore(
            JdbcTemplate jdbcTemplate,
            JdbcSchemaInitializer schemaInitializer,
            JdbcStoreProperties properties) {
        return new JdbcModelCallUsageStore(jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeFileStore.class)
    public JdbcKnowledgeFileStore jdbcKnowledgeFileStore(
            JdbcTemplate jdbcTemplate,
            JdbcSchemaInitializer schemaInitializer,
            JdbcStoreProperties properties) {
        return new JdbcKnowledgeFileStore(jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean(SkillManifestStore.class)
    public SkillManifestStore jdbcSkillManifestStore(
            JdbcTemplate db,
            JdbcSchemaInitializer schemaInitializer,
            JdbcStoreProperties properties) {
        JdbcKnowledgeFileStore store = new JdbcKnowledgeFileStore(db, properties);
        return new SkillManifestStore() {
            @Override
            public java.util.List<SkillManifest> listManifests() {
                return store.listManifests();
            }

            @Override
            public java.util.List<SkillManifest> listManifests(KnowledgeScope scope) {
                return store.listManifests(scope);
            }
        };
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RunStopCoordinator.class)
    public JdbcRunStopCoordinator jdbcRunStopCoordinator(
            JdbcTemplate jdbcTemplate,
            JdbcSchemaInitializer schemaInitializer,
            JdbcStoreProperties storeProperties,
            JdbcRunStopProperties properties) {
        return new JdbcRunStopCoordinator(jdbcTemplate, storeProperties, properties);
    }

    @Bean
    @ConditionalOnMissingBean(RunInputCoordinator.class)
    public JdbcRunInputCoordinator jdbcRunInputCoordinator(
            JdbcTemplate jdbcTemplate,
            JdbcSchemaInitializer schemaInitializer,
            ObjectMapper objectMapper,
            JdbcStoreProperties storeProperties) {
        return new JdbcRunInputCoordinator(jdbcTemplate, objectMapper, storeProperties);
    }

    @Bean
    @ConditionalOnMissingBean(ToolApprovalCoordinator.class)
    public JdbcToolApprovalCoordinator jdbcToolApprovalCoordinator(
            JdbcTemplate jdbcTemplate,
            JdbcSchemaInitializer schemaInitializer,
            ObjectMapper objectMapper,
            JdbcStoreProperties storeProperties,
            JdbcToolApprovalProperties properties) {
        return new JdbcToolApprovalCoordinator(
                jdbcTemplate, objectMapper, storeProperties, properties);
    }
}
