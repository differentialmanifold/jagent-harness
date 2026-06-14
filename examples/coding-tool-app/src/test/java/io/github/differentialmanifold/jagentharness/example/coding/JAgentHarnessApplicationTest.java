package io.github.differentialmanifold.jagentharness.example.coding;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.message.MessageRepository;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:sqlite:target/context.db",
                "spring.datasource.driver-class-name=org.sqlite.JDBC"
        })
class JAgentHarnessApplicationTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private MessageRepository messageRepository;

    @Test
    void contextLoads() {
        assertNotNull(toolRegistry.get("skill"));
        assertNotNull(toolRegistry.get("read"));

        AgentMessage message = AgentMessage.assistant("stop-reason-test", "", Collections.emptyList());
        message.setStopReason(AgentMessage.STOP_REASON_ABORTED);
        messageRepository.append(message);

        assertTrue(messageRepository.findBySessionId("stop-reason-test").stream()
                .anyMatch(stored -> message.getMessageId().equals(stored.getMessageId())
                        && AgentMessage.STOP_REASON_ABORTED.equals(stored.getStopReason())));
    }
}
