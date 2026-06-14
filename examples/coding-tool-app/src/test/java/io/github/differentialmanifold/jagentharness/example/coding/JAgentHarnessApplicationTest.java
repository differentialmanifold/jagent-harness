package io.github.differentialmanifold.jagentharness.example.coding;

import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    @Test
    void contextLoads() {
        assertNotNull(toolRegistry.get("skill"));
        assertNotNull(toolRegistry.get("read"));
    }
}
