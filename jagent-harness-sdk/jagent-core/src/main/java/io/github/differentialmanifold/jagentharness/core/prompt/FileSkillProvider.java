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
        Path normalizedPath = normalize(path);

        return SkillFileParser.readDescriptor(
                content,
                defaultName,
                normalizedPath.toString());
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
}
