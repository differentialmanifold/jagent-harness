package io.github.differentialmanifold.jagentharness.core.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.support.PathsSupport;
import org.yaml.snakeyaml.Yaml;

public class FileSkillProvider implements SkillProvider {

    private final Path configRoot;
    private final String skillsDir;

    public FileSkillProvider() {
        this(PathsSupport.expandUserHome(PathsSupport.DEFAULT_CONFIG_ROOT), "skills");
    }

    public FileSkillProvider(Path configRoot, String skillsDir) {
        this.configRoot = normalize(configRoot == null ? PathsSupport.expandUserHome(PathsSupport.DEFAULT_CONFIG_ROOT) : configRoot);
        this.skillsDir = skillsDir == null || skillsDir.trim().isEmpty() ? "skills" : skillsDir.trim();
    }

    public List<SkillDescriptor> listSkills() {
        return listSkillsAtRoots(configRoot);
    }

    public List<SkillDescriptor> listSkills(Path root) {
        return listSkillsAtRoots(root);
    }

    @Override
    public List<SkillDescriptor> listSkills(AgentContext context) {
        Path effectiveConfigRoot = context == null || context.getConfigRoot() == null
                ? configRoot
                : normalize(context.getConfigRoot());
        Path workspaceRoot = context == null ? null : normalize(context.getWorkspaceRoot());
        return listSkillsAtRoots(effectiveConfigRoot, workspaceRoot);
    }

    private List<SkillDescriptor> listSkillsAtRoots(Path... roots) {
        Map<String, SkillDescriptor> skills = new LinkedHashMap<String, SkillDescriptor>();
        if (roots == null) {
            return new ArrayList<SkillDescriptor>();
        }
        for (Path root : roots) {
            if (root == null) {
                continue;
            }
            Path skillsRoot = normalize(root).resolve(skillsDir).normalize();
            for (SkillDescriptor skill : listSkillsAtRoot(skillsRoot)) {
                skills.remove(skill.getName());
                skills.put(skill.getName(), skill);
            }
        }
        return new ArrayList<SkillDescriptor>(skills.values());
    }

    private List<SkillDescriptor> listSkillsAtRoot(Path skillsRoot) {
        List<SkillDescriptor> skills = new ArrayList<SkillDescriptor>();
        if (!Files.isDirectory(skillsRoot)) {
            return skills;
        }
        try (Stream<Path> stream = Files.walk(skillsRoot)) {
            List<Path> paths = new ArrayList<Path>();
            stream.filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .forEach(paths::add);
            Collections.sort(paths, Comparator.comparing(path -> normalize(path).toString()));
            for (Path path : paths) {
                skills.add(readDescriptor(path));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan skills under " + skillsRoot, e);
        }
        return skills;
    }

    private SkillDescriptor readDescriptor(Path path) {
        String defaultName = path.getParent() == null ? "skill" : path.getParent().getFileName().toString();
        String content = readFile(path);

        SkillFile skillFile = parseSkillFile(content);
        MarkdownDescriptor markdownDescriptor = readMarkdownDescriptor(skillFile.getBody());
        String name = firstNonBlank(
                metadataString(skillFile.getMetadata(), "name"),
                markdownDescriptor.getName(),
                defaultName);
        String description = firstNonBlank(
                metadataString(skillFile.getMetadata(), "description"),
                markdownDescriptor.getDescription());
        Path normalizedPath = normalize(path);
        Path directory = normalize(path.getParent());
        return new SkillDescriptor(
                name,
                description,
                normalizedPath.toString(),
                directory == null ? null : directory.toString());
    }

    private SkillFile parseSkillFile(String content) {
        String[] lines = content.split("\\r?\\n", -1);
        if (lines.length == 0 || !"---".equals(lines[0].trim())) {
            return new SkillFile(Collections.<String, Object>emptyMap(), content);
        }

        StringBuilder metadata = new StringBuilder();
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if ("---".equals(trimmed) || "...".equals(trimmed)) {
                end = i;
                break;
            }
            metadata.append(lines[i]).append('\n');
        }

        if (end < 0) {
            return new SkillFile(Collections.<String, Object>emptyMap(), content);
        }

        StringBuilder body = new StringBuilder();
        for (int i = end + 1; i < lines.length; i++) {
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(lines[i]);
        }
        return new SkillFile(parseMetadata(metadata.toString()), body.toString());
    }

    private Map<String, Object> parseMetadata(String content) {
        Object parsed = new Yaml().load(content);
        if (!(parsed instanceof Map)) {
            return Collections.emptyMap();
        }

        Map<?, ?> values = (Map<?, ?>) parsed;
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() != null) {
                metadata.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return metadata;
    }

    private MarkdownDescriptor readMarkdownDescriptor(String content) {
        String name = "";
        String description = "";
        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ") && name.isEmpty()) {
                name = trimmed.substring(2).trim();
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                description = trimmed;
                break;
            }
        }
        return new MarkdownDescriptor(name, description);
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private String readFile(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static class SkillFile {

        private final Map<String, Object> metadata;
        private final String body;

        private SkillFile(Map<String, Object> metadata, String body) {
            this.metadata = metadata;
            this.body = body == null ? "" : body;
        }

        private Map<String, Object> getMetadata() {
            return metadata;
        }

        private String getBody() {
            return body;
        }
    }

    private static class MarkdownDescriptor {

        private final String name;
        private final String description;

        private MarkdownDescriptor(String name, String description) {
            this.name = name == null ? "" : name;
            this.description = description == null ? "" : description;
        }

        private String getName() {
            return name;
        }

        private String getDescription() {
            return description;
        }
    }
}
