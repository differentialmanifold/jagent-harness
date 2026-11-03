package io.github.differentialmanifold.jagentharness.core.prompt;

import io.github.differentialmanifold.jagentharness.core.skill.SkillToolDependency;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class SkillFileParser {

    private SkillFileParser() {}

    public static SkillDescriptor readDescriptor(
            String content, String defaultName, String filePath) {
        SkillFile skillFile = parseSkillFile(content);
        MarkdownDescriptor markdownDescriptor = readMarkdownDescriptor(skillFile.getBody());
        String name =
                firstNonBlank(
                        metadataString(skillFile.getMetadata(), "name"),
                        markdownDescriptor.getName(),
                        defaultName);
        String description =
                firstNonBlank(
                        metadataString(skillFile.getMetadata(), "description"),
                        markdownDescriptor.getDescription());
        return new SkillDescriptor(name, description, filePath);
    }

    public static List<SkillToolDependency> readToolDependencies(String content) {
        Map<String, Object> metadata = parseSkillFile(content).getMetadata();
        if (!metadata.containsKey("tool_dependencies")) return Collections.emptyList();
        Object raw = metadata.get("tool_dependencies");
        if (!(raw instanceof List))
            throw new IllegalArgumentException("tool_dependencies must be a list");
        Map<String, LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map))
                throw new IllegalArgumentException(
                        "Each tool dependency must contain server and tools");
            Map<?, ?> entry = (Map<?, ?>) item;
            for (Object key : entry.keySet()) {
                if (!"server".equals(key) && !"tools".equals(key))
                    throw new IllegalArgumentException("Unknown tool dependency field: " + key);
            }
            String server = dependencyName(entry.get("server"), "server");
            if (!server.matches("[A-Za-z0-9_-]{1,64}"))
                throw new IllegalArgumentException("Invalid MCP dependency server: " + server);
            Object names = entry.get("tools");
            if (!(names instanceof List) || ((List<?>) names).isEmpty())
                throw new IllegalArgumentException(
                        "Dependency tools must be a non-empty list: " + server);
            LinkedHashSet<String> tools =
                    grouped.computeIfAbsent(server, ignored -> new LinkedHashSet<>());
            for (Object name : (List<?>) names) tools.add(dependencyName(name, "tool"));
        }
        List<SkillToolDependency> result = new ArrayList<>();
        grouped.forEach(
                (server, tools) ->
                        result.add(new SkillToolDependency(server, new ArrayList<>(tools))));
        return Collections.unmodifiableList(result);
    }

    private static String dependencyName(Object value, String field) {
        if (!(value instanceof String)
                || ((String) value).trim().isEmpty()
                || "*".equals(((String) value).trim())) {
            throw new IllegalArgumentException(
                    "Dependency " + field + " must be an exact non-empty name (no wildcard)");
        }
        return ((String) value).trim();
    }

    private static SkillFile parseSkillFile(String content) {
        String effectiveContent = content == null ? "" : content;
        String[] lines = effectiveContent.split("\\r?\\n", -1);
        if (lines.length == 0 || !"---".equals(lines[0].trim())) {
            return new SkillFile(Collections.<String, Object>emptyMap(), effectiveContent);
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
            return new SkillFile(Collections.<String, Object>emptyMap(), effectiveContent);
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

    private static Map<String, Object> parseMetadata(String content) {
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(content);
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

    private static MarkdownDescriptor readMarkdownDescriptor(String content) {
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

    private static String metadataString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
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
