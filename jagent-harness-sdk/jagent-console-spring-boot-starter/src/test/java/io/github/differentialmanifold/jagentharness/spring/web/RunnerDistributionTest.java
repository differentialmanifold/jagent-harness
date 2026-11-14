package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RunnerDistributionTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private final byte[] jar = "verified offline package".getBytes(StandardCharsets.UTF_8);

    private MockMvc mvc() throws Exception {
        Path directory = temp.resolve("runners/coding");
        Files.createDirectories(directory);
        Files.write(directory.resolve("runner.jar"), jar);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("kind", "coding");
        info.put("version", "2.2.0");
        info.put("artifact", "runner.jar");
        info.put("javaVersion", 8);
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(jar))
            hash.append(String.format("%02x", b & 255));
        info.put("sha256", hash.toString());
        json.writeValue(directory.resolve("manifest.json").toFile(), info);
        ConsoleProperties properties = new ConsoleProperties();
        properties.setRunnerDistributionDirectory(temp.resolve("runners").toString());
        return MockMvcBuilders.standaloneSetup(new RunnerDistributionController(properties, json))
                .setControllerAdvice(new ProtocolErrors())
                .addFilters(new ApiAuthenticationFilter("test-token", "127.0.0.1"))
                .build();
    }

    @Test
    void servesVerifiedOfflineFilesOnlyWithAuthentication() throws Exception {
        MockMvc http = mvc();
        String prefix = "/api/v1/distributions/runners/coding/";
        http.perform(get(prefix + "manifest.json")).andExpect(status().isUnauthorized());
        http.perform(get(prefix + "runner.jar").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
        http.perform(get(prefix + "manifest.json").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2.2.0"))
                .andExpect(header().string("Cache-Control", "no-store"));
        http.perform(get(prefix + "runner.jar").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(jar));
        http.perform(get(prefix + "config.json").header("Authorization", "Bearer test-token"))
                .andExpect(status().isNotFound());
        http.perform(
                        get("/api/v1/distributions/runners/missing/runner.jar")
                                .header("Authorization", "Bearer test-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesCorruptPackagesAndPathsOutsideDistributionRoot() throws Exception {
        MockMvc http = mvc();
        Path file = temp.resolve("runners/coding/runner.jar");
        Files.write(file, new byte[] {1});
        http.perform(
                        get("/api/v1/distributions/runners/coding/manifest.json")
                                .header("Authorization", "Bearer test-token"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RUNNER_DISTRIBUTION_INVALID"));
        Files.delete(file);
        Path outside = temp.resolve("outside.jar");
        Files.write(outside, jar);
        Files.createSymbolicLink(file, outside);
        http.perform(
                        get("/api/v1/distributions/runners/coding/runner.jar")
                                .header("Authorization", "Bearer test-token"))
                .andExpect(status().isNotFound());
    }
}
