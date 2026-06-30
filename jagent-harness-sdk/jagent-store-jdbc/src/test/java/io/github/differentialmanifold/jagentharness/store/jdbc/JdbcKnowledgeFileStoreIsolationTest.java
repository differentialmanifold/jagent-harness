package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileConflictException;

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
    void conditionallyWritesKnowledgeFileByContentHash() {
        JdbcKnowledgeFileStore store = store(createDatabase(), "mcp-app");

        KnowledgeFile created = store.writeFile("mcp.json", "{\"version\":1}", "application/json", null);
        assertNotNull(created.getContentHash());

        KnowledgeFile updated = store.writeFile(
                "mcp.json",
                "{\"version\":2}",
                "application/json",
                created.getContentHash());
        assertEquals("{\"version\":2}", updated.getContent());
        assertThrows(KnowledgeFileConflictException.class, () -> store.writeFile(
                "mcp.json",
                "{\"version\":3}",
                "application/json",
                created.getContentHash()));
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
