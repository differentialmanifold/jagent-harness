package io.github.differentialmanifold.jagentharness.spring.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.execution.ProtocolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Operator-provided offline packages, protected by the ordinary API authentication filter. */
@RestController
@RequestMapping("/api/v1/distributions/runners")
public class RunnerDistributionController {
    private final Path root;
    private final ObjectMapper json;

    public RunnerDistributionController(ConsoleProperties properties, ObjectMapper json) {
        root = Paths.get(properties.getRunnerDistributionDirectory()).toAbsolutePath().normalize();
        this.json = json;
    }

    @GetMapping("/{kind}/{file}")
    public ResponseEntity<Resource> download(@PathVariable String kind, @PathVariable String file) {
        if (!kind.matches("[a-z][a-z0-9-]{0,63}")
                || !("manifest.json".equals(file) || "runner.jar".equals(file))) throw missing();
        Path directory = root.resolve(kind);
        if (!Files.isRegularFile(directory.resolve("manifest.json"))
                || !Files.isRegularFile(directory.resolve("runner.jar"))) throw missing();
        try {
            Path realRoot = root.toRealPath();
            Path manifest = directory.resolve("manifest.json").toRealPath();
            Path jar = directory.resolve("runner.jar").toRealPath();
            if (!manifest.startsWith(realRoot) || !jar.startsWith(realRoot)) throw missing();
            if (Files.size(manifest) > 16384) throw invalid();
            JsonNode info = json.readTree(manifest.toFile());
            if (info == null
                    || !kind.equals(info.path("kind").asText())
                    || !"runner.jar".equals(info.path("artifact").asText())
                    || !info.path("version").asText().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                    || info.path("javaVersion").asInt() < 8
                    || !sha256(jar).equals(info.path("sha256").asText())) throw invalid();
            Path selected = "runner.jar".equals(file) ? jar : manifest;
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .contentType(
                            "runner.jar".equals(file)
                                    ? MediaType.APPLICATION_OCTET_STREAM
                                    : MediaType.APPLICATION_JSON)
                    .contentLength(Files.size(selected))
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(file).build().toString())
                    .body(new FileSystemResource(selected));
        } catch (IOException e) {
            throw invalid();
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ProtocolException missing() {
        return new ProtocolException(
                404,
                "RUNNER_DISTRIBUTION_NOT_FOUND",
                "Runner package is unavailable. Install its runner.jar and manifest.json in the configured distribution directory.");
    }

    private static ProtocolException invalid() {
        return new ProtocolException(
                503,
                "RUNNER_DISTRIBUTION_INVALID",
                "Runner package is incomplete or failed verification. Replace the package and its manifest together.");
    }
}
