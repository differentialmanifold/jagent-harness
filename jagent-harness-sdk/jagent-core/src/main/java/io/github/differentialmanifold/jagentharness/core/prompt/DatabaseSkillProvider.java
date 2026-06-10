package io.github.differentialmanifold.jagentharness.core.prompt;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;

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

        List<SkillDescriptor> skills = new ArrayList<SkillDescriptor>();
        for (SkillManifest manifest : manifestStore.listManifests()) {
            String name = firstNonBlank(manifest.getName(), manifest.getSkillKey());
            String description = firstNonBlank(manifest.getDescription());
            skills.add(new SkillDescriptor(name, description, manifest.getSkillFilePath()));
        }
        return skills;
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
