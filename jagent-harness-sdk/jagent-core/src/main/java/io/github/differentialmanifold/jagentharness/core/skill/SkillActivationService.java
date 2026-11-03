package io.github.differentialmanifold.jagentharness.core.skill;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillFileParser;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public class SkillActivationService {
    private final SkillActivationStore store;
    private final Supplier<SkillToolResolver> resolver;

    public SkillActivationService(
            SkillActivationStore store, Supplier<SkillToolResolver> resolver) {
        this.store = store;
        this.resolver = resolver;
    }

    public List<SkillToolBinding> activate(
            AgentContext context, String path, String source, String content) {
        List<SkillToolDependency> dependencies = SkillFileParser.readToolDependencies(content);
        List<SkillToolBinding> bindings = Collections.emptyList();
        if (!dependencies.isEmpty()) {
            SkillToolResolver available = resolver.get();
            if (available == null)
                throw new IllegalStateException(
                        "Skill requires MCP tools but no SkillToolResolver is configured");
            bindings = available.resolve(context, dependencies);
        }
        if (context == null
                || context.getSessionId() == null
                || context.getSessionId().trim().isEmpty()) {
            if (!dependencies.isEmpty())
                throw new IllegalStateException("A session is required to activate skill tools");
            return bindings;
        }
        // Save only after the entire dependency set has been resolved successfully.
        store.activate(
                context.getSessionId(),
                SkillActivationStore.scopeKey(context),
                new SkillActivation(path, source, SkillActivation.digest(content), bindings));
        return bindings;
    }
}
