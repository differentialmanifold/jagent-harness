package io.github.differentialmanifold.jagentharness.mcp.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@AutoConfigureAfter(name = {
        "io.github.differentialmanifold.jagentharness.spring.AgentHarnessAutoConfiguration",
        "io.github.differentialmanifold.jagentharness.store.jdbc.JdbcStoreAutoConfiguration"
})
@EnableConfigurationProperties(McpProperties.class)
@ConditionalOnProperty(prefix = "harness.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public McpConfigurationManager mcpConfigurationManager(AgentSettings settings,
                                                           McpProperties properties,
                                                           ObjectProvider<KnowledgeFileStore> knowledgeFileStore,
                                                           ObjectMapper objectMapper) {
        return new McpConfigurationManager(
                settings.getConfigRoot(),
                properties.getConfigFile(),
                knowledgeFileStore.getIfAvailable(),
                objectMapper);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public McpRuntime mcpRuntime(McpConfigurationManager configurationManager,
                                 ObjectMapper objectMapper) {
        return new McpRuntime(configurationManager, objectMapper);
    }
}
