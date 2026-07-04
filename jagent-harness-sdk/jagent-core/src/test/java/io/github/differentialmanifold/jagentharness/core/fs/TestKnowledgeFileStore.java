package io.github.differentialmanifold.jagentharness.core.fs;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.core.prompt.SkillDescriptor;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillFileParser;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillManifest;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillManifestStore;

public class TestKnowledgeFileStore implements KnowledgeFileStore, SkillManifestStore {

    private final Map<String, KnowledgeFile> files = new LinkedHashMap<String, KnowledgeFile>();
    private final Map<String, SkillManifest> skillManifests = new LinkedHashMap<String, SkillManifest>();

    @Override
    public KnowledgeFile readFile(String path) {
        return readFile(KnowledgeScope.global(), path);
    }

    @Override
    public KnowledgeFile readFile(KnowledgeScope scope, String path) {
        KnowledgeFile file = files.get(key(scope, KnowledgeFilePaths.normalize(path)));
        return file != null && KnowledgeFile.TYPE_FILE.equals(file.getNodeType()) ? file : null;
    }

    @Override
    public List<KnowledgeFile> listFiles(String prefix) {
        return listFiles(KnowledgeScope.global(), prefix);
    }

    @Override
    public List<KnowledgeFile> listFiles(KnowledgeScope scope, String prefix) {
        String normalizedPrefix = KnowledgeFilePaths.normalizePrefix(prefix);
        List<KnowledgeFile> values = new ArrayList<KnowledgeFile>();
        String scopePrefix = scopeKey(scope) + ":";
        for (Map.Entry<String, KnowledgeFile> entry : files.entrySet()) {
            if (!entry.getKey().startsWith(scopePrefix)) continue;
            KnowledgeFile file = entry.getValue();
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
        return writeFile(KnowledgeScope.global(), path, content, contentType);
    }

    @Override
    public KnowledgeFile writeFile(KnowledgeScope scope, String path, String content, String contentType) {
        String normalizedPath = KnowledgeFilePaths.normalize(path);
        ensureDirectories(scope, normalizedPath);
        Instant now = Instant.now();
        KnowledgeFile existing = readFile(scope, normalizedPath);
        KnowledgeFile file = new KnowledgeFile(
                normalizedPath,
                KnowledgeFile.TYPE_FILE,
                content,
                contentType,
                existing == null ? now : existing.getCreatedAt(),
                now);
        files.put(key(scope, file.getPath()), file);
        syncIndexes(scope, file);
        return file;
    }

    @Override
    public void deleteFile(String path) {
        deleteFile(KnowledgeScope.global(), path);
    }

    @Override
    public void deleteFile(KnowledgeScope scope, String path) {
        String normalizedPath = KnowledgeFilePaths.normalize(path);
        files.remove(key(scope, normalizedPath));
        if (KnowledgeFilePaths.isSkillManifestFile(normalizedPath)) {
            skillManifests.remove(key(scope, KnowledgeFilePaths.skillKey(normalizedPath)));
        }
    }

    @Override
    public List<SkillManifest> listManifests() {
        return listManifests(KnowledgeScope.global());
    }

    @Override
    public List<SkillManifest> listManifests(KnowledgeScope scope) {
        List<SkillManifest> manifests = new ArrayList<SkillManifest>();
        String scopePrefix = scopeKey(scope) + ":";
        for (Map.Entry<String, SkillManifest> entry : skillManifests.entrySet()) {
            if (entry.getKey().startsWith(scopePrefix)) manifests.add(entry.getValue());
        }
        Collections.sort(manifests, Comparator.comparing(SkillManifest::getSkillKey));
        return manifests;
    }

    private void ensureDirectories(KnowledgeScope scope, String path) {
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
            if (!files.containsKey(key(scope, dir))) {
                Instant now = Instant.now();
                files.put(key(scope, dir), new KnowledgeFile(
                        dir,
                        KnowledgeFile.TYPE_DIRECTORY,
                        "",
                        "inode/directory",
                        now,
                        now));
            }
        }
    }

    private void syncIndexes(KnowledgeScope scope, KnowledgeFile file) {
        if (KnowledgeFilePaths.isSkillManifestFile(file.getPath())) {
            String skillKey = KnowledgeFilePaths.skillKey(file.getPath());
            SkillDescriptor descriptor = SkillFileParser.readDescriptor(file.getContent(), skillKey, file.getPath());
            skillManifests.put(key(scope, skillKey), new SkillManifest(
                    skillKey,
                    KnowledgeFilePaths.skillDir(file.getPath()),
                    file.getPath(),
                    descriptor.getName(),
                    descriptor.getDescription(),
                    file.getUpdatedAt()));
        }
    }

    private String key(KnowledgeScope scope, String path) {
        return scopeKey(scope) + ":" + path;
    }

    private String scopeKey(KnowledgeScope scope) {
        KnowledgeScope effective = scope == null ? KnowledgeScope.global() : scope;
        return effective.getType() + ":" + effective.getId();
    }
}
