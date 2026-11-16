package io.github.differentialmanifold.jagentharness.core.personal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.prompt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned support workflows available only to the built-in mainbot identity. */
public final class PersonalAgentSkills implements SkillProvider {
    private static final String ENVIRONMENT = "skills/jagent-help/references/environment.json";
    private final PersonalAgentStore store;
    private final Map<String, String> resources = new LinkedHashMap<>();
    private final List<SkillDescriptor> descriptors = new ArrayList<>();
    private final Properties build = new Properties();

    public PersonalAgentSkills(PersonalAgentStore store) {
        this.store = store;
        for (String name : Arrays.asList("jagent-help", "runner-setup")) {
            String path = "skills/" + name + "/SKILL.md";
            String content = resource(path);
            resources.put(path, content);
            SkillDescriptor parsed = SkillFileParser.readDescriptor(content, name, path);
            descriptors.add(
                    new SkillDescriptor(
                            parsed.getName(), parsed.getDescription(), path, "builtin"));
        }
        try (InputStream input =
                PersonalAgentSkills.class.getResourceAsStream("/jagent-build.properties")) {
            if (input != null) build.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load Harness build information", e);
        }
    }

    private String resource(String path) {
        try (InputStream input = PersonalAgentSkills.class.getResourceAsStream("/" + path)) {
            if (input == null) throw new IllegalStateException("Missing bundled skill: " + path);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load bundled skill: " + path, e);
        }
    }

    @Override
    public List<SkillDescriptor> listSkills(AgentContext context) {
        return PersonalAgentService.MAINBOT_ID.equals(PersonalTool.identity(store, context))
                ? Collections.unmodifiableList(descriptors)
                : Collections.emptyList();
    }

    @Override
    public String readResource(AgentContext context, String path) {
        if (!PersonalAgentService.MAINBOT_ID.equals(PersonalTool.identity(store, context)))
            return null;
        return ENVIRONMENT.equals(path) ? environment() : resources.get(path);
    }

    private String environment() {
        PersonalAgentStore.Profile p = store.profile(PersonalAgentService.MAINBOT_ID);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("harnessVersion", build.getProperty("version", "unknown"));
        data.put("harnessRevision", build.getProperty("revision", "unknown"));
        data.put(
                "repository",
                build.getProperty(
                        "repository", "https://github.com/differentialmanifold/jagent-harness"));
        data.put("runnerId", p == null ? null : p.runnerId);
        data.put("sourceWorkspace", p == null ? null : p.sourceWorkspace);
        data.put("sourceVersion", p == null ? null : p.sourceVersion);
        data.put("sourceRevision", p == null ? null : p.sourceRevision);
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(data);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Configuration browsing uses the same identity scope, without activating a Skill. */
    public List<KnowledgeFile> bundledFiles(String agentId) {
        if (!PersonalAgentService.MAINBOT_ID.equals(agentId)) return Collections.emptyList();
        List<KnowledgeFile> files = new ArrayList<>();
        resources.forEach(
                (path, content) ->
                        files.add(
                                new KnowledgeFile(
                                        path,
                                        KnowledgeFile.TYPE_FILE,
                                        content,
                                        "text/markdown",
                                        null,
                                        null)));
        files.add(
                new KnowledgeFile(
                        ENVIRONMENT,
                        KnowledgeFile.TYPE_FILE,
                        environment(),
                        "application/json",
                        null,
                        null));
        return files;
    }
}
