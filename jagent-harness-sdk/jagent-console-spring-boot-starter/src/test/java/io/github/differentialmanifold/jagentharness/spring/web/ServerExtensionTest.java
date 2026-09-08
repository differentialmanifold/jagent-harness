package io.github.differentialmanifold.jagentharness.spring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.prompt.*;
import io.github.differentialmanifold.jagentharness.core.session.SessionRepository;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import io.github.differentialmanifold.jagentharness.spring.ModelAccessTokenProvider;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;

class ServerExtensionTest {
    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withUserConfiguration(
                            TestServerApplication.class, ConversationProtocolTest.Fixtures.class)
                    .withPropertyValues(
                            "spring.datasource.url=jdbc:h2:mem:extensions;DB_CLOSE_DELAY=-1",
                            "spring.datasource.driver-class-name=org.h2.Driver",
                            "server.address=127.0.0.1");

    @Test
    void consumerBeansReplaceDefaultsAndCustomToolsAreRegistered() {
        runner.withUserConfiguration(Overrides.class)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context)
                                    .hasSingleBean(PromptProvider.class)
                                    .hasSingleBean(SessionRepository.class)
                                    .hasSingleBean(ModelAccessTokenProvider.class)
                                    .hasSingleBean(SkillManifestStore.class);
                            assertThat(
                                            context.getBean(PromptProvider.class)
                                                    .buildSystemPrompt(null))
                                    .isEqualTo("user prompt");
                            assertThat(context.getBean(SessionRepository.class))
                                    .isSameAs(context.getBean("userSessions"));
                            assertThat(
                                            context.getBean(ModelAccessTokenProvider.class)
                                                    .getAccessToken())
                                    .isEqualTo("user token");
                            assertThat(context.getBean(ToolRegistry.class).get("server_fixture"))
                                    .isNotNull();
                            assertThat(context).hasSingleBean(KnowledgeFileStore.class);
                            assertThat(context)
                                    .doesNotHaveBean("jdbcSessionRepository")
                                    .doesNotHaveBean("promptProvider");
                        });
    }

    @Test
    void knowledgeAndSkillStoresCanBeReplacedIndependently() {
        runner.withBean(
                        "customKnowledge",
                        KnowledgeFileStore.class,
                        () -> mock(KnowledgeFileStore.class))
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(KnowledgeFileStore.class)
                                    .hasSingleBean(SkillManifestStore.class)
                                    .doesNotHaveBean("jdbcKnowledgeFileStore");
                        });
    }

    @Test
    void starterCanBeDisabledByHost() {
        runner.withPropertyValues("agent.enabled=false")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed().doesNotHaveBean(ChatService.class);
                        });
    }

    @Test
    void unspecifiedBindAddressRequiresAuthentication() {
        new WebApplicationContextRunner()
                .withUserConfiguration(TestServerApplication.class)
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseMessage(
                                            "agent.protocol.token is required for a non-loopback server binding");
                        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Overrides {
        @Bean
        PromptProvider userPrompt() {
            return context -> "user prompt";
        }

        @Bean
        SessionRepository userSessions() {
            return mock(SessionRepository.class);
        }

        @Bean
        ModelAccessTokenProvider userToken() {
            return () -> "user token";
        }

        @Bean
        SkillManifestStore userSkills() {
            return Collections::emptyList;
        }
    }
}
