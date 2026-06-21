package io.github.differentialmanifold.jagentharness.example.businessconsole;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

@Component
public class DemoSkillSeeder implements ApplicationRunner {

    private static final String RESOURCE_ROOT = "business-console-demo/";
    private static final String[] SKILL_PATHS = {
            "skills/shopping-assistant/SKILL.md",
            "skills/shopping-assistant/recommendation-rules.md"
    };

    private final KnowledgeFileStore knowledgeFileStore;

    public DemoSkillSeeder(KnowledgeFileStore knowledgeFileStore) {
        this.knowledgeFileStore = knowledgeFileStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String path : SKILL_PATHS) {
            Resource resource = new ClassPathResource(RESOURCE_ROOT + path);
            if (!resource.exists() || !resource.isReadable()) {
                throw new IllegalStateException("Business console demo skill resource not found: classpath:"
                        + RESOURCE_ROOT + path);
            }
            knowledgeFileStore.writeFile(path, read(resource), contentType(path));
        }
    }

    private String read(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read business console demo skill resource "
                    + resource.getDescription(), e);
        }
    }

    private String contentType(String path) {
        return path.endsWith(".md") ? "text/markdown" : "text/plain";
    }
}
