package io.github.differentialmanifold.jagentharness.example.business;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillManifestStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:business-demo-test?mode=memory&cache=shared",
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
        assertNotNull(knowledgeFileStore.readFile("skills/shopping-assistant/SKILL.md"));
        assertNotNull(knowledgeFileStore.readFile("skills/shopping-assistant/recommendation-rules.md"));
        assertEquals(1, skillManifestStore.listManifests().size());
        assertEquals("Shopping Assistant", skillManifestStore.listManifests().get(0).getName());
    }
}
