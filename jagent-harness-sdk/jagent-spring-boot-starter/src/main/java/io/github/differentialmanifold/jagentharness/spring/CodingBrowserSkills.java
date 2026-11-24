package io.github.differentialmanifold.jagentharness.spring;

import io.github.differentialmanifold.jagentharness.core.agent.AgentContext;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillDescriptor;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillFileParser;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillProvider;
import io.github.differentialmanifold.jagentharness.core.runner.RunnerStore;
import io.github.differentialmanifold.jagentharness.core.skill.SkillActivation;
import io.github.differentialmanifold.jagentharness.protocol.RunnerInfo;
import io.github.differentialmanifold.jagentharness.protocol.RunnerProject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

/** Built-in browser Skills for Coding Projects with a local shell. */
public final class CodingBrowserSkills implements SkillProvider {
    private static final String ROOT = "/jagent-coding/";
    private final RunnerStore runners;
    private final Map<String, KnowledgeFile> files;
    private final List<SkillDescriptor> descriptors;

    public CodingBrowserSkills(RunnerStore runners) throws IOException {
        this.runners = runners;
        Map<String, KnowledgeFile> loaded = new TreeMap<>();
        List<SkillDescriptor> manifests = new ArrayList<>();
        Resource[] resources =
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:" + ROOT + "skills/**/*.md");
        for (Resource resource : resources) {
            String url = resource.getURL().toExternalForm();
            String path = url.substring(url.lastIndexOf(ROOT) + ROOT.length());
            try (InputStream in = resource.getInputStream()) {
                String content = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
                loaded.put(
                        path,
                        new KnowledgeFile(
                                path,
                                KnowledgeFile.TYPE_FILE,
                                content,
                                "text/markdown",
                                null,
                                null));
                if (path.endsWith("/SKILL.md")) {
                    SkillDescriptor skill =
                            SkillFileParser.readDescriptor(content, path.split("/")[1], path);
                    manifests.add(
                            new SkillDescriptor(
                                    skill.getName(), skill.getDescription(), path, "builtin"));
                }
            }
        }
        files = Collections.unmodifiableMap(loaded);
        descriptors = Collections.unmodifiableList(manifests);
    }

    public Map<String, KnowledgeFile> filesForProject(String projectId) {
        return available(projectId) ? files : Collections.emptyMap();
    }

    private boolean available(String projectId) {
        if (projectId == null || projectId.isEmpty()) return false;
        RunnerProject project = runners.project(projectId);
        if (project == null || !project.enabledTools.contains("bash")) return false;
        RunnerInfo runner = runners.runner(project.runnerId);
        return runner != null
                && (runner.capabilities.contains("coding") || runner.capabilities.contains("browser-research"))
                && runner.tools.stream().anyMatch(tool -> "bash".equals(tool.name));
    }

    private boolean available(AgentContext context) {
        return context != null
                && context.getAttribute("personalAgentId") == null
                && available(context.getProjectId());
    }

    @Override
    public List<SkillDescriptor> listSkills(AgentContext context) {
        return available(context) ? descriptors : Collections.emptyList();
    }

    @Override
    public String readResource(AgentContext context, String path) {
        KnowledgeFile file = files.get(path);
        if (file == null || !available(context)) return null;
        String content = file.getContent();
        if (!content.contains("{{browserSession}}")) return content;
        String sessionId = context.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) return null;
        // Keep the same session name as existing Coding Runners, including across upgrades.
        String browserSession = "ja-" + SkillActivation.digest(sessionId).substring(0, 20);
        return content.replace("{{browserSession}}", browserSession);
    }
}
