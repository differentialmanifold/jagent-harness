package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFilePaths;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillFileParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Resolves skill roots before importing, preserving paths relative to each SKILL.md. */
final class SkillArchive {
    private static final int MAX_ENTRIES = 2048;
    private static final int MAX_CONTENT_BYTES = 32 * 1024 * 1024;

    private SkillArchive() {}

    static Map<String, String> read(InputStream input, String filename) throws IOException {
        Map<String, String> files = new LinkedHashMap<String, String>();
        int entries = 0;
        int contentBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IllegalArgumentException("Skills zip contains too many entries.");
                }
                String path = entry.getName().replace('\\', '/');
                if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
                    throw new IllegalArgumentException("Skills zip paths must be relative: " + path);
                }
                if (entry.isDirectory() && path.matches("(?:\\./)*\\.?")) {
                    zip.closeEntry();
                    continue;
                }
                path = KnowledgeFilePaths.normalize(path);
                boolean ignored = isMetadata(path) || entry.isDirectory();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    contentBytes += read;
                    if (contentBytes > MAX_CONTENT_BYTES) {
                        throw new IllegalArgumentException("Skills zip exceeds 32 MiB of extracted content.");
                    }
                    if (!ignored) {
                        output.write(buffer, 0, read);
                    }
                }
                if (!ignored && files.put(path, new String(output.toByteArray(), StandardCharsets.UTF_8)) != null) {
                    throw new IllegalArgumentException("Duplicate skills zip path: " + path);
                }
                zip.closeEntry();
            }
        }

        Map<String, String> roots = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            if ("SKILL.md".equals(KnowledgeFilePaths.fileName(file.getKey()))) {
                String root = KnowledgeFilePaths.parent(file.getKey());
                String name;
                if (root.isEmpty() || "skills".equals(root)) {
                    String fallback = archiveName(filename);
                    name = skillName(SkillFileParser.readDescriptor(file.getValue(), fallback, file.getKey()).getName());
                } else {
                    name = KnowledgeFilePaths.fileName(root);
                }
                roots.put(root, "skills/" + name + "/");
            }
        }
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("Skills zip must contain a SKILL.md file.");
        }

        Map<String, String> imported = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> file : files.entrySet()) {
            String owner = null;
            for (String root : roots.keySet()) {
                if ((root.isEmpty() || file.getKey().startsWith(root + "/"))
                        && (owner == null || root.length() > owner.length())) {
                    owner = root;
                }
            }
            if (owner == null) {
                continue;
            }
            String relative = owner.isEmpty() ? file.getKey() : file.getKey().substring(owner.length() + 1);
            String path = KnowledgeFilePaths.normalize(roots.get(owner) + relative);
            if (imported.put(path, file.getValue()) != null) {
                throw new IllegalArgumentException("Multiple archive entries map to the same skill path: " + path);
            }
        }
        return imported;
    }

    private static boolean isMetadata(String path) {
        for (String part : path.split("/")) {
            if ("__MACOSX".equals(part) || ".DS_Store".equals(part) || part.startsWith("._")) {
                return true;
            }
        }
        return false;
    }

    private static String archiveName(String filename) {
        String name = filename == null ? "imported-skill" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceFirst("(?i)\\.zip$", "");
        return skillName(name);
    }

    private static String skillName(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}_-]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "imported-skill" : slug;
    }
}
