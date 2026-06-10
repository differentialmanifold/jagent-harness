package io.github.differentialmanifold.jagentharness.core.fs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.core.prompt.PromptBinding;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptBindingStore;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillDescriptor;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillFileParser;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillManifest;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillManifestStore;

public class TestKnowledgeFileStore implements KnowledgeFileStore, SkillManifestStore, PromptBindingStore {

    private final Map<String, KnowledgeFile> files = new LinkedHashMap<String, KnowledgeFile>();
    private final Map<String, SkillManifest> skillManifests = new LinkedHashMap<String, SkillManifest>();
    private final Map<String, PromptBinding> promptBindings = new LinkedHashMap<String, PromptBinding>();

    @Override
    public KnowledgeFile readFile(String path) {
        KnowledgeFile file = files.get(KnowledgeFilePaths.normalize(path));
        return file != null && KnowledgeFile.TYPE_FILE.equals(file.getNodeType()) ? file : null;
    }

    @Override
    public List<KnowledgeFile> listFiles(String prefix) {
        String normalizedPrefix = KnowledgeFilePaths.normalizePrefix(prefix);
        List<KnowledgeFile> values = new ArrayList<KnowledgeFile>();
        for (KnowledgeFile file : files.values()) {
            if (!KnowledgeFile.TYPE_FILE.equals(file.getNodeType())) {
                continue;
            }
            if (normalizedPrefix.isEmpty()
                    || file.getPath().equals(normalizedPrefix)
                    || file.getPath().startsWith(normalizedPrefix + "/")) {
                values.add(file);
            }
        }
        Collections.sort(values, Comparator.comparing(KnowledgeFile::getPath));
        return values;
    }

    @Override
    public KnowledgeFile writeFile(String path, String content, String contentType) {
        String normalizedPath = KnowledgeFilePaths.normalize(path);
        ensureDirectories(normalizedPath);
        Instant now = Instant.now();
        KnowledgeFile existing = readFile(normalizedPath);
        KnowledgeFile file = new KnowledgeFile(
                normalizedPath,
                KnowledgeFile.TYPE_FILE,
                content,
                contentType,
                existing == null ? now : existing.getCreatedAt(),
                now);
        files.put(file.getPath(), file);
        syncIndexes(file);
        return file;
    }

    @Override
    public void deleteFile(String path) {
        String normalizedPath = KnowledgeFilePaths.normalize(path);
        files.remove(normalizedPath);
        promptBindings.remove(normalizedPath);
        if (KnowledgeFilePaths.isSkillManifestFile(normalizedPath)) {
            skillManifests.remove(KnowledgeFilePaths.skillKey(normalizedPath));
        }
    }

    @Override
    public List<SkillManifest> listManifests() {
        List<SkillManifest> manifests = new ArrayList<SkillManifest>(skillManifests.values());
        Collections.sort(manifests, Comparator.comparing(SkillManifest::getSkillKey));
        return manifests;
    }

    @Override
    public List<PromptBinding> listBindings(String promptName) {
        List<PromptBinding> bindings = new ArrayList<PromptBinding>();
        for (PromptBinding binding : promptBindings.values()) {
            if (binding.getPromptName().equals(promptName)) {
                bindings.add(binding);
            }
        }
        Collections.sort(bindings, Comparator.comparing(PromptBinding::getPriority)
                .thenComparing(PromptBinding::getFilePath));
        return bindings;
    }

    private void ensureDirectories(String path) {
        String parent = KnowledgeFilePaths.parent(path);
        if (parent.isEmpty()) {
            return;
        }
        String[] segments = parent.split("/");
        StringBuilder current = new StringBuilder();
        for (String segment : segments) {
            if (current.length() > 0) {
                current.append('/');
            }
            current.append(segment);
            String dir = current.toString();
            if (!files.containsKey(dir)) {
                Instant now = Instant.now();
                files.put(dir, new KnowledgeFile(
                        dir,
                        KnowledgeFile.TYPE_DIRECTORY,
                        "",
                        "inode/directory",
                        now,
                        now));
            }
        }
    }

    private void syncIndexes(KnowledgeFile file) {
        if ("AGENTS.md".equals(file.getPath())) {
            promptBindings.put(file.getPath(), new PromptBinding("AGENTS.md", file.getPath(), 100, file.getUpdatedAt()));
        }
        if (KnowledgeFilePaths.isSkillManifestFile(file.getPath())) {
            String skillKey = KnowledgeFilePaths.skillKey(file.getPath());
            SkillDescriptor descriptor = SkillFileParser.readDescriptor(file.getContent(), skillKey, file.getPath());
            skillManifests.put(skillKey, new SkillManifest(
                    skillKey,
                    KnowledgeFilePaths.skillDir(file.getPath()),
                    file.getPath(),
                    descriptor.getName(),
                    descriptor.getDescription(),
                    file.getUpdatedAt()));
        }
    }
}
