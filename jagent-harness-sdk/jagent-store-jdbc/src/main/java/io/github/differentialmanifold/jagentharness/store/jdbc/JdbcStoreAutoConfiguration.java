package io.github.differentialmanifold.jagentharness.store.jdbc;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopCoordinator;
import io.github.differentialmanifold.jagentharness.core.conversation.CompactionStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.message.MessageRepository;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillManifestStore;
import io.github.differentialmanifold.jagentharness.core.session.SessionRepository;
import io.github.differentialmanifold.jagentharness.core.timeline.TimelineEventRepository;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalCoordinator;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsageStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnClass(JdbcTemplate.class)
@EnableConfigurationProperties({JdbcStoreProperties.class, JdbcRunStopProperties.class, JdbcToolApprovalProperties.class})
public class JdbcStoreAutoConfiguration {

    @Bean(initMethod = "initialize")
    @ConditionalOnMissingBean
    public JdbcSchemaInitializer jdbcSchemaInitializer(DataSource dataSource) {
        return new JdbcSchemaInitializer(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(SessionRepository.class)
    public JdbcSessionRepository jdbcSessionRepository(JdbcTemplate jdbcTemplate,
                                                       JdbcStoreProperties properties) {
        return new JdbcSessionRepository(jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean(MessageRepository.class)
    public JdbcMessageRepository jdbcMessageRepository(JdbcTemplate jdbcTemplate,
                                                       ObjectMapper objectMapper,
                                                       JdbcStoreProperties properties) {
        return new JdbcMessageRepository(jdbcTemplate, objectMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean(TimelineEventRepository.class)
    public JdbcTimelineEventRepository jdbcTimelineEventRepository(JdbcTemplate jdbcTemplate,
                                                                   JdbcStoreProperties properties) {
        return new JdbcTimelineEventRepository(jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean(CompactionStore.class)
    public JdbcCompactionStore jdbcCompactionStore(JdbcTemplate jdbcTemplate,
                                                   JdbcStoreProperties properties) {
        return new JdbcCompactionStore(jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean(ModelCallUsageStore.class)
    public JdbcModelCallUsageStore jdbcModelCallUsageStore(JdbcTemplate jdbcTemplate,
                                                           JdbcStoreProperties properties) {
        return new JdbcModelCallUsageStore(jdbcTemplate, properties);
    }

    @Bean
    @ConditionalOnMissingBean({KnowledgeFileStore.class, SkillManifestStore.class})
    public JdbcKnowledgeFileStore jdbcKnowledgeFileStore(JdbcTemplate jdbcTemplate,
                                                         JdbcStoreProperties properties) {
        return new JdbcKnowledgeFileStore(jdbcTemplate, properties);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RunStopCoordinator.class)
    public JdbcRunStopCoordinator jdbcRunStopCoordinator(JdbcTemplate jdbcTemplate,
                                                         JdbcStoreProperties storeProperties,
                                                         JdbcRunStopProperties properties) {
        return new JdbcRunStopCoordinator(jdbcTemplate, storeProperties, properties);
    }

    @Bean
    @ConditionalOnMissingBean(ToolApprovalCoordinator.class)
    public JdbcToolApprovalCoordinator jdbcToolApprovalCoordinator(JdbcTemplate jdbcTemplate,
                                                                   ObjectMapper objectMapper,
                                                                   JdbcStoreProperties storeProperties,
                                                                   JdbcToolApprovalProperties properties) {
        return new JdbcToolApprovalCoordinator(jdbcTemplate, objectMapper, storeProperties, properties);
    }
}
