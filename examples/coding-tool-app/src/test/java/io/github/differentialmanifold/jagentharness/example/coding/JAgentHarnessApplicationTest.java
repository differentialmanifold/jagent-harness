package io.github.differentialmanifold.jagentharness.example.coding;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:sqlite:target/context.db",
                "spring.datasource.driver-class-name=org.sqlite.JDBC"
        })
class JAgentHarnessApplicationTest {

    @Test
    void contextLoads() {
    }
}
