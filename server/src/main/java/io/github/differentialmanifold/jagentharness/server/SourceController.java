package io.github.differentialmanifold.jagentharness.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnResource(resources = "classpath:META-INF/jagent/source.properties")
@RestController
@RequestMapping("/api/v1/source")
public class SourceController {
    private final Resource archive = new ClassPathResource("META-INF/jagent/source.zip");
    private final Properties manifest = new Properties();

    public SourceController() throws IOException {
        try (InputStream input = new ClassPathResource("META-INF/jagent/source.properties").getInputStream()) {
            manifest.load(input);
        }
    }

    @GetMapping("/manifest")
    public Properties manifest() {
        return manifest;
    }

    @GetMapping(value = "/archive", produces = "application/zip")
    public Resource archive() {
        return archive;
    }
}
