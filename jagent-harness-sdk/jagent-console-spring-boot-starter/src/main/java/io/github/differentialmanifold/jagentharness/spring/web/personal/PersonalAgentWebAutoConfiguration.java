package io.github.differentialmanifold.jagentharness.spring.web.personal;

import io.github.differentialmanifold.jagentharness.core.personal.*;
import io.github.differentialmanifold.jagentharness.spring.personal.PersonalAgentAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = PersonalAgentAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(PersonalAgentService.class)
@ConditionalOnProperty(
        prefix = "harness.console",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PersonalAgentWebAutoConfiguration {
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
    public PersonalAgentErrors personalAgentErrors() {
        return new PersonalAgentErrors();
    }
}
