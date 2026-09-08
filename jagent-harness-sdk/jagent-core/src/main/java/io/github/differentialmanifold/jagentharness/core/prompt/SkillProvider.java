package io.github.differentialmanifold.jagentharness.core.prompt;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import java.util.List;

public interface SkillProvider {

    List<SkillDescriptor> listSkills(AgentContext context);
}
