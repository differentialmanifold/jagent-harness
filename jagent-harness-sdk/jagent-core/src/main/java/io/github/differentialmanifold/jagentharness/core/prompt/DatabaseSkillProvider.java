package io.github.differentialmanifold.jagentharness.core.prompt;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeScope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        KnowledgeScope defaults = KnowledgeScope.globalFor(context);
        addSkills(skills, manifestStore.listManifests(defaults), defaults.getType());
        if (defaults.equals(KnowledgeScope.global())
                && context != null
                && context.getProjectId() != null
                && !context.getProjectId().trim().isEmpty()) {
            addSkills(
                    skills,
                    manifestStore.listManifests(KnowledgeScope.project(context.getProjectId())),
                    "project");
        }
        String personal =
                context == null ? null : context.getAttribute("personalAgentId", String.class);
        if (personal != null)
            addSkills(skills, manifestStore.listManifests(KnowledgeScope.agent(personal)), "agent");
        return new ArrayList<SkillDescriptor>(skills.values());
    }

    private void addSkills(
            Map<String, SkillDescriptor> skills, List<SkillManifest> manifests, String source) {
        for (SkillManifest manifest : manifests) {
            String name = firstNonBlank(manifest.getName(), manifest.getSkillKey());
            String description = firstNonBlank(manifest.getDescription());
            skills.remove(name);
            skills.put(
                    name,
                    new SkillDescriptor(name, description, manifest.getSkillFilePath(), source));
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
