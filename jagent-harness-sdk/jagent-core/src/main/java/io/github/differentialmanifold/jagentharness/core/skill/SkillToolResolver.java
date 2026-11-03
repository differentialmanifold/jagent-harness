package io.github.differentialmanifold.jagentharness.core.skill;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import java.util.List;

public interface SkillToolResolver {
    /** Validate every dependency against current configuration, without activating any tools. */
    List<SkillToolBinding> resolve(AgentContext context, List<SkillToolDependency> dependencies);
}
