package io.github.differentialmanifold.jagentharness.spring.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

class SkillArchiveTest {
    @ParameterizedTest
    @ValueSource(strings = {"skills/example/", "example/", "download-main/skills/example/", "./example/"})
    void importsWrappedSkillsWithoutChangingRelativeReferences(String root) throws Exception {
        Map<String, String> files = read("bundle.zip",
                root + "references/guide.md", "Supporting text",
                root + "SKILL.md", "# Example\nRead references/guide.md");
        assertThat(files).containsOnlyKeys("skills/example/SKILL.md", "skills/example/references/guide.md");
        assertThat(files.get("skills/example/references/guide.md")).isEqualTo("Supporting text");
    }

    @Test
    void importsFlatArchiveUsingDescriptorNameRegardlessOfEntryOrder() throws Exception {
        Map<String, String> files = read("download.zip",
                "references/guide.md", "Guide",
                "scripts/run.sh", "echo hello",
                "SKILL.md", "---\nname: flat-skill\ndescription: Flat archive\n---\nRead references/guide.md");
        assertThat(files).containsOnlyKeys("skills/flat-skill/SKILL.md",
                "skills/flat-skill/references/guide.md", "skills/flat-skill/scripts/run.sh");
    }

    @Test
    void fallsBackToArchiveNameWhenFlatSkillHasNoName() throws Exception {
        assertThat(read("My Skill.zip", "SKILL.md", "Instructions only."))
                .containsKey("skills/my-skill/SKILL.md");
    }

    @Test
    void importsMultipleSkillsAndIgnoresPackagingMetadata() throws Exception {
        Map<String, String> files = read("bundle.zip",
                "bundle/README.md", "Package README",
                "bundle/skills/one/SKILL.md", "# One",
                "bundle/skills/two/SKILL.md", "# Two",
                "bundle/skills/one/.DS_Store", "metadata",
                "__MACOSX/._SKILL.md", "metadata");
        assertThat(files).containsOnlyKeys("skills/one/SKILL.md", "skills/two/SKILL.md");
    }

    @Test
    void handlesRootDirectoryEntry() throws Exception {
        assertThat(read("example.zip", "./", "", "./SKILL.md", "# Example"))
                .containsKey("skills/example/SKILL.md");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil.md", "example/../../evil.md", "/tmp/evil.md", "C:\\evil.md"})
    void rejectsUnsafePaths(String path) {
        assertThatThrownBy(() -> read("skill.zip", "SKILL.md", "# Test", path, "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsArchivesWithoutSkillEntryPoint() {
        assertThatThrownBy(() -> read("skill.zip", "README.md", "No skill"))
                .hasMessageContaining("SKILL.md");
    }

    @Test
    void rejectsCollidingSkillRoots() {
        assertThatThrownBy(() -> read("skill.zip",
                "one/example/SKILL.md", "# One", "two/example/SKILL.md", "# Two"))
                .hasMessageContaining("same skill path");
    }

    @Test
    void validatesArchiveBeforeWritingToKnowledgeStore() throws Exception {
        KnowledgeFileStore store = mock(KnowledgeFileStore.class);
        VirtualFileController controller = new VirtualFileController(store, mock(SessionManager.class));
        MockMultipartFile file = new MockMultipartFile("file", "skill.zip", "application/zip",
                zip("example/SKILL.md", "# Example", "../evil.md", "bad"));
        assertThatThrownBy(() -> controller.importSkills(file, "global", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }

    @Test
    void controllerWritesNormalizedPathsAndContents() throws Exception {
        KnowledgeFileStore store = mock(KnowledgeFileStore.class);
        VirtualFileController controller = new VirtualFileController(store, mock(SessionManager.class));
        MockMultipartFile file = new MockMultipartFile("file", "skill.zip", "application/zip",
                zip("SKILL.md", "# Example", "references/guide.md", "Guide"));
        controller.importSkills(file, "global", null);
        verify(store).writeFile(any(KnowledgeScope.class), eq("skills/example/SKILL.md"), eq("# Example"), eq("text/markdown"));
        verify(store).writeFile(any(KnowledgeScope.class), eq("skills/example/references/guide.md"), eq("Guide"), eq("text/markdown"));
        verifyNoMoreInteractions(store);
    }

    private Map<String, String> read(String filename, String... entries) throws Exception {
        return SkillArchive.read(new ByteArrayInputStream(zip(entries)), filename);
    }

    private byte[] zip(String... entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry(entries[i]));
                zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
