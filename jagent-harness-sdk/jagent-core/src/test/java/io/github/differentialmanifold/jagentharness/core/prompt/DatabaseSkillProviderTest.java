package io.github.differentialmanifold.jagentharness.core.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import io.github.differentialmanifold.jagentharness.core.fs.TestKnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import org.junit.jupiter.api.Test;

class DatabaseSkillProviderTest {

    @Test
    void readsSkillDescriptorsFromDatabaseFiles() {
        TestKnowledgeFileStore store = new TestKnowledgeFileStore();
        KnowledgeFile file = store.writeFile(
                "skills/review/SKILL.md",
                "---\nname: review\ndescription: Review code from the database.\n---\n\n# Review\n",
                "text/markdown");

        List<SkillDescriptor> skills = new DatabaseSkillProvider(store).listSkills(null);

        assertEquals("skills/review/SKILL.md", file.getPath());
        assertEquals(1, skills.size());
        assertEquals("review", skills.get(0).getName());
        assertEquals("Review code from the database.", skills.get(0).getDescription());
        assertEquals("skills/review/SKILL.md", skills.get(0).getFilePath());
    }
}
