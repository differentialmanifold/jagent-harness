package io.github.differentialmanifold.jagentharness.core.prompt;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;

public class SkillRegistry {

    private final List<SkillProvider> providers = new ArrayList<SkillProvider>();

    public SkillRegistry(List<SkillProvider> providers) {
        if (providers != null) {
            for (SkillProvider provider : providers) {
                register(provider);
            }
        }
    }

    public synchronized void register(SkillProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("skillProvider must not be null");
        }
        if (!providers.contains(provider)) {
            providers.add(provider);
        }
    }

    public List<SkillDescriptor> listSkills(AgentContext context) {
        List<SkillProvider> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<SkillProvider>(providers);
        }

        Map<String, RankedSkill> skills = new LinkedHashMap<String, RankedSkill>();
        int sequence = 0;
        for (SkillProvider provider : snapshot) {
            List<SkillDescriptor> provided = provider.listSkills(context);
            if (provided != null) {
                for (SkillDescriptor skill : provided) {
                    if (skill == null) {
                        continue;
                    }
                    String key = skillKey(skill, sequence);
                    RankedSkill candidate = new RankedSkill(skill, skillPriority(skill, context), sequence);
                    RankedSkill existing = skills.get(key);
                    if (existing == null || candidate.compareTo(existing) >= 0) {
                        skills.put(key, candidate);
                    }
                    sequence += 1;
                }
            }
        }

        List<SkillDescriptor> result = new ArrayList<SkillDescriptor>();
        for (RankedSkill skill : skills.values()) {
            result.add(skill.getSkill());
        }
        return result;
    }

    private String skillKey(SkillDescriptor skill, int sequence) {
        String name = skill.getName();
        if (name == null || name.trim().isEmpty()) {
            return "@unnamed-" + sequence;
        }
        return name.trim();
    }

    private int skillPriority(SkillDescriptor skill, AgentContext context) {
        String filePath = skill.getFilePath();
        if (filePath == null || filePath.trim().isEmpty()) {
            return 0;
        }
        Path rawPath = parsePath(filePath);
        if (rawPath == null) {
            return 0;
        }
        if (!rawPath.isAbsolute()) {
            return 300;
        }
        Path path = rawPath.toAbsolutePath().normalize();

        Path workspaceRoot = normalize(context == null ? null : context.getWorkspaceRoot());
        if (workspaceRoot != null && path.startsWith(workspaceRoot)) {
            return 200;
        }

        Path configRoot = normalize(context == null ? null : context.getConfigRoot());
        if (configRoot != null && path.startsWith(configRoot)) {
            return 100;
        }

        return 0;
    }

    private Path parsePath(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            return null;
        }
        try {
            return Paths.get(filePath.trim()).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static class RankedSkill implements Comparable<RankedSkill> {

        private final SkillDescriptor skill;
        private final int priority;
        private final int sequence;

        private RankedSkill(SkillDescriptor skill, int priority, int sequence) {
            this.skill = skill;
            this.priority = priority;
            this.sequence = sequence;
        }

        private SkillDescriptor getSkill() {
            return skill;
        }

        @Override
        public int compareTo(RankedSkill other) {
            if (priority != other.priority) {
                return priority - other.priority;
            }
            return sequence - other.sequence;
        }
    }
}
