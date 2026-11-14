package io.github.differentialmanifold.jagentharness.core.personal;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillDescriptor;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillFileParser;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** Optional built-in workflows. Only their descriptors enter the system prompt. */
public final class PersonalAgentSkills implements SkillProvider {
    private static final String PATH = "skills/runner-setup/SKILL.md";
    private final PersonalAgentStore store;
    private final String content;
    private final SkillDescriptor descriptor;

    public PersonalAgentSkills(PersonalAgentStore store) {
        this.store = store;
        try (InputStream input = PersonalAgentSkills.class.getResourceAsStream("/" + PATH)) {
            if (input == null) throw new IllegalStateException("Missing bundled skill: " + PATH);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            content = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load bundled skill: " + PATH, e);
        }
        SkillDescriptor parsed = SkillFileParser.readDescriptor(content, "runner-setup", PATH);
        descriptor =
                new SkillDescriptor(parsed.getName(), parsed.getDescription(), PATH, "builtin");
    }

    @Override
    public List<SkillDescriptor> listSkills(AgentContext context) {
        return PersonalTool.identity(store, context) == null
                ? Collections.emptyList()
                : Collections.singletonList(descriptor);
    }

    @Override
    public String readResource(AgentContext context, String path) {
        return PATH.equals(path) && PersonalTool.identity(store, context) != null ? content : null;
    }

    /** Read-only configuration browsing; does not activate a Skill in a conversation. */
    public List<KnowledgeFile> bundledFiles() {
        return Collections.singletonList(
                new KnowledgeFile(
                        PATH, KnowledgeFile.TYPE_FILE, content, "text/markdown", null, null));
    }
}
