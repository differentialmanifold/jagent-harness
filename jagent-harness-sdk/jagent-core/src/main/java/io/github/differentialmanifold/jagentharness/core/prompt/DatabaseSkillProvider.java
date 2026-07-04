package io.github.differentialmanifold.jagentharness.core.prompt;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;

public class DatabaseSkillProvider implements SkillProvider {

    private final SkillManifestStore manifestStore;

    public DatabaseSkillProvider(SkillManifestStore manifestStore) {
        this.manifestStore = manifestStore;
    }

    @Override
    public List<SkillDescriptor> listSkills(AgentContext context) {
        if (manifestStore == null) {
            return Collections.emptyList();
        }

        Map<String, SkillDescriptor> skills = new LinkedHashMap<String, SkillDescriptor>();
        addSkills(skills, manifestStore.listManifests(KnowledgeScope.global()), "global");
        if (context != null && context.getProjectId() != null && !context.getProjectId().trim().isEmpty()) {
            addSkills(skills, manifestStore.listManifests(KnowledgeScope.project(context.getProjectId())), "project");
        }
        return new ArrayList<SkillDescriptor>(skills.values());
    }

    private void addSkills(Map<String, SkillDescriptor> skills, List<SkillManifest> manifests, String source) {
        for (SkillManifest manifest : manifests) {
            String name = firstNonBlank(manifest.getName(), manifest.getSkillKey());
            String description = firstNonBlank(manifest.getDescription());
            skills.remove(name);
            skills.put(name, new SkillDescriptor(name, description, manifest.getSkillFilePath(), source));
        }
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
}
