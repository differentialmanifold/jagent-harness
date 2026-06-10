package io.github.differentialmanifold.jagentharness.core.prompt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

public final class SkillFileParser {

    private SkillFileParser() {
    }

    public static SkillDescriptor readDescriptor(String content,
                                                 String defaultName,
                                                 String filePath) {
        SkillFile skillFile = parseSkillFile(content);
        MarkdownDescriptor markdownDescriptor = readMarkdownDescriptor(skillFile.getBody());
        String name = firstNonBlank(
                metadataString(skillFile.getMetadata(), "name"),
                markdownDescriptor.getName(),
                defaultName);
        String description = firstNonBlank(
                metadataString(skillFile.getMetadata(), "description"),
                markdownDescriptor.getDescription());
        return new SkillDescriptor(name, description, filePath);
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
