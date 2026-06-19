package io.github.differentialmanifold.jagentharness.example.business;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.support.PathsSupport;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DemoSkillSeeder implements ApplicationRunner {

    private final KnowledgeFileStore knowledgeFileStore;
    private final BusinessSystemDemoProperties properties;

    public DemoSkillSeeder(KnowledgeFileStore knowledgeFileStore,
                           BusinessSystemDemoProperties properties) {
        this.knowledgeFileStore = knowledgeFileStore;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path skillsRoot = resolveSkillsRoot();
        if (!Files.isDirectory(skillsRoot)) {
            throw new IllegalStateException("Business demo skills directory not found: " + skillsRoot);
        }

        for (Path path : regularFiles(skillsRoot)) {
            String logicalPath = "skills/" + toUnixPath(skillsRoot.relativize(path));
            knowledgeFileStore.writeFile(logicalPath, read(path), contentType(path));
        }
    }

    private Path resolveSkillsRoot() {
        Path configured = PathsSupport.expandUserHome(properties.getSkillsSource());
        if (Files.isDirectory(configured)) {
            return configured;
        }
        Path moduleLocal = PathsSupport.expandUserHome("skills");
        if (Files.isDirectory(moduleLocal)) {
            return moduleLocal;
        }
        return configured;
    }

    private List<Path> regularFiles(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> files = new ArrayList<Path>();
            stream.filter(Files::isRegularFile).forEach(files::add);
            Collections.sort(files);
            return files;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan business demo skills under " + root, e);
        }
    }

    private String read(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read business demo skill file " + path, e);
        }
    }

    private String contentType(Path path) {
        return path.getFileName().toString().endsWith(".md") ? "text/markdown" : "text/plain";
    }

    private String toUnixPath(Path path) {
        return path.toString().replace('\\', '/');
    }
}
