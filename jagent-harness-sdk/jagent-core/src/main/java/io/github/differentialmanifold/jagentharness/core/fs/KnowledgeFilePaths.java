package io.github.differentialmanifold.jagentharness.core.fs;

import java.util.ArrayList;
import java.util.List;

public final class KnowledgeFilePaths {

    private KnowledgeFilePaths() {
    }

    public static String normalize(String path) {
        String value = path == null ? "" : path.trim().replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Knowledge file path is required.");
        }

        String[] segments = value.split("/");
        List<String> normalized = new ArrayList<String>();
        for (String segment : segments) {
            String part = segment.trim();
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new IllegalArgumentException("Knowledge file path cannot contain '..': " + path);
            }
            normalized.add(part);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Knowledge file path is required.");
        }
        return join(normalized);
    }

    public static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            return "";
        }
        return normalize(prefix);
    }

    public static String fileName(String path) {
        String normalized = normalize(path);
        int index = normalized.lastIndexOf('/');
        return index < 0 ? normalized : normalized.substring(index + 1);
    }

    public static String parent(String path) {
        String normalized = normalize(path);
        int index = normalized.lastIndexOf('/');
        return index < 0 ? "" : normalized.substring(0, index);
    }

    public static String skillKey(String skillFilePath) {
        String[] parts = normalize(skillFilePath).split("/");
        if (parts.length != 3 || !"skills".equals(parts[0]) || !"SKILL.md".equals(parts[2])) {
            throw new IllegalArgumentException("Skill file path must look like skills/{skill}/SKILL.md: " + skillFilePath);
        }
        return parts[1];
    }

    public static String skillDir(String skillFilePath) {
        return parent(skillFilePath);
    }

    public static boolean isSkillManifestFile(String path) {
        try {
            String normalized = normalize(path);
            String[] parts = normalized.split("/");
            return parts.length == 3 && "skills".equals(parts[0]) && "SKILL.md".equals(parts[2]);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String join(List<String> segments) {
        StringBuilder value = new StringBuilder();
        for (String segment : segments) {
            if (value.length() > 0) {
                value.append('/');
            }
            value.append(segment);
        }
        return value.toString();
    }
}
