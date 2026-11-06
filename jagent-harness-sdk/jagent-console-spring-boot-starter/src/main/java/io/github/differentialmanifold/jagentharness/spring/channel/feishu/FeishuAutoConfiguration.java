package io.github.differentialmanifold.jagentharness.spring.channel.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.personal.*;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore;
import io.github.differentialmanifold.jagentharness.spring.personal.PersonalAgentAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.*;

@AutoConfiguration(after = PersonalAgentAutoConfiguration.class)
@ConditionalOnBean(PersonalAgentService.class)
@ConditionalOnClass(name = "com.lark.oapi.channel.LarkChannel")
@ConditionalOnProperty(prefix = "harness.personal.feishu", name = "enabled", matchIfMissing = true)
public class FeishuAutoConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    public FeishuService feishuService(
            PersonalAgentService agents, PersonalAgentStore store, ObjectMapper json) {
        return new FeishuService(agents, store, json);
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnProperty(
            prefix = "harness.console",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    public FeishuController feishuController(FeishuService service) {
        return new FeishuController(service);
    }
}
