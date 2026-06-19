package io.github.differentialmanifold.jagentharness.example.business;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillManifestStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/business-demo-context.db",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.main.web-application-type=none",
        "harness.store.jdbc.application-id=business-system-agent-demo"
})
class BusinessSystemAgentDemoApplicationTest {

    @Autowired
    private KnowledgeFileStore knowledgeFileStore;

    @Autowired
    private SkillManifestStore skillManifestStore;

    @Test
    void seedsSourceSkillsIntoApplicationScopedDatabaseStore() {
        assertNotNull(knowledgeFileStore.readFile("skills/customer-support/SKILL.md"));
        assertNotNull(knowledgeFileStore.readFile("skills/customer-support/refund-guidelines.md"));
        assertEquals(1, skillManifestStore.listManifests().size());
        assertEquals("Customer Support Workflow", skillManifestStore.listManifests().get(0).getName());
    }
}
