package io.github.differentialmanifold.jagentharness.store.jdbc;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopCoordinator;
import io.github.differentialmanifold.jagentharness.core.conversation.CompactionStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.message.MessageRepository;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptBindingStore;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillManifestStore;
import io.github.differentialmanifold.jagentharness.core.session.SessionRepository;
import io.github.differentialmanifold.jagentharness.core.timeline.TimelineEventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnClass(JdbcTemplate.class)
@EnableConfigurationProperties(JdbcRunStopProperties.class)
public class JdbcStoreAutoConfiguration {

    @Bean(initMethod = "initialize")
    @ConditionalOnMissingBean
    public JdbcSchemaInitializer jdbcSchemaInitializer(DataSource dataSource) {
        return new JdbcSchemaInitializer(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(SessionRepository.class)
    public JdbcSessionRepository jdbcSessionRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcSessionRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(MessageRepository.class)
    public JdbcMessageRepository jdbcMessageRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcMessageRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(TimelineEventRepository.class)
    public JdbcTimelineEventRepository jdbcTimelineEventRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcTimelineEventRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(CompactionStore.class)
    public JdbcCompactionStore jdbcCompactionStore(JdbcTemplate jdbcTemplate) {
        return new JdbcCompactionStore(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean({KnowledgeFileStore.class, SkillManifestStore.class, PromptBindingStore.class})
    public JdbcKnowledgeFileStore jdbcKnowledgeFileStore(JdbcTemplate jdbcTemplate) {
        return new JdbcKnowledgeFileStore(jdbcTemplate);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(RunStopCoordinator.class)
    public JdbcRunStopCoordinator jdbcRunStopCoordinator(JdbcTemplate jdbcTemplate,
                                                         JdbcRunStopProperties properties) {
        return new JdbcRunStopCoordinator(jdbcTemplate, properties);
    }
}
