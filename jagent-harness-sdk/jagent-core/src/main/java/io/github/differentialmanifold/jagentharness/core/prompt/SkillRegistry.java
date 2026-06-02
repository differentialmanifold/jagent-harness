package io.github.differentialmanifold.jagentharness.core.prompt;

import java.util.ArrayList;
import java.util.List;

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

        List<SkillDescriptor> skills = new ArrayList<SkillDescriptor>();
        for (SkillProvider provider : snapshot) {
            List<SkillDescriptor> provided = provider.listSkills(context);
            if (provided != null) {
                skills.addAll(provided);
            }
        }
        return skills;
    }
}
