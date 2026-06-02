package io.github.differentialmanifold.jagentharness.core.prompt;

import java.util.List;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;

public interface SkillProvider {

    List<SkillDescriptor> listSkills(AgentContext context);
}
