package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcKnowledgeFileStoreIsolationTest {

    @TempDir
    Path tempDir;

    @Test
    void isolatesKnowledgeFilesAndSkillManifestsByApplicationId() {
        JdbcTemplate jdbcTemplate = createDatabase();
        JdbcKnowledgeFileStore coding = store(jdbcTemplate, "coding-tool-app");
        JdbcKnowledgeFileStore business = store(jdbcTemplate, "business-system-agent-demo");

        coding.writeFile(
                "skills/customer-support/SKILL.md",
                "---\nname: Coding Skill\n---\nCoding instructions",
                "text/markdown");
        business.writeFile(
                "skills/customer-support/SKILL.md",
                "---\nname: Business Skill\n---\nBusiness instructions",
                "text/markdown");

        assertEquals("Coding instructions", content(coding, "skills/customer-support/SKILL.md"));
        assertEquals("Business instructions", content(business, "skills/customer-support/SKILL.md"));
        assertEquals("Coding Skill", coding.listManifests().get(0).getName());
        assertEquals("Business Skill", business.listManifests().get(0).getName());

        business.deleteFile("skills/customer-support/SKILL.md");

        assertNull(business.readFile("skills/customer-support/SKILL.md"));
        assertEquals(0, business.listManifests().size());
        assertEquals("Coding instructions", content(coding, "skills/customer-support/SKILL.md"));
        assertEquals(1, coding.listManifests().size());
    }

    @Test
    void isolatesGlobalAndProjectKnowledgeScopes() {
        JdbcKnowledgeFileStore store = store(createDatabase(), "coding-tool-app");
        KnowledgeScope project = KnowledgeScope.project("project-1");

        store.writeFile(KnowledgeScope.global(), "AGENTS.md", "Global rules", "text/markdown");
        store.writeFile(project, "AGENTS.md", "Project rules", "text/markdown");
        store.writeFile(KnowledgeScope.global(), "skills/review/SKILL.md",
                "---\nname: Global Review\n---\n", "text/markdown");
        store.writeFile(project, "skills/review/SKILL.md",
                "---\nname: Project Review\n---\n", "text/markdown");

        assertEquals("Global rules", store.readFile(KnowledgeScope.global(), "AGENTS.md").getContent());
        assertEquals("Project rules", store.readFile(project, "AGENTS.md").getContent());
        assertEquals("Global Review", store.listManifests(KnowledgeScope.global()).get(0).getName());
        assertEquals("Project Review", store.listManifests(project).get(0).getName());
    }

    private String content(JdbcKnowledgeFileStore store, String path) {
        return store.readFile(path).getContent().replaceFirst("(?s)^---.*?---\\s*", "");
    }

    private JdbcTemplate createDatabase() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("knowledge-isolation.db"));
        new JdbcSchemaInitializer(dataSource).initialize();
        return new JdbcTemplate(dataSource);
    }

    private JdbcKnowledgeFileStore store(JdbcTemplate jdbcTemplate, String applicationId) {
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId(applicationId);
        return new JdbcKnowledgeFileStore(jdbcTemplate, properties);
    }
}
