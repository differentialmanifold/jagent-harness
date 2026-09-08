package io.github.differentialmanifold.jagentharness.spring.web;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = TestServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:conversation;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "agent.model.provider=scripted",
            "agent.model.model=test",
            "agent.compaction.enabled=false",
            "agent.protocol.token=test-token"
        })
class H2ConversationProtocolTest extends ConversationProtocolTest {}
