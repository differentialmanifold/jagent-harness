package io.github.differentialmanifold.jagentharness.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;

/** Browser access for an existing Runner. Does not create an identity or open its work journal. */
public final class RunnerConsole {
    private final HttpRunnerTransport http;
    public final String userId;
    public final String runnerId;

    public RunnerConsole(String server, Path stateRoot, String token, String expectedUser)
            throws IOException {
        server = RunnerFiles.serverUrl(server);
        if (!Files.isDirectory(AgentRunner.stateDirectory(server, stateRoot)))
            throw new IOException(
                    "No local Runner profile for this Harness. Start jagent runner first.");
        http = new HttpRunnerTransport(server, token);
        userId = http.management("GET", "/api/v1/access/identity", null).path("userId").asText();
        if (userId.isEmpty() || expectedUser != null && !expectedUser.equals(userId))
            throw new IOException("Credential belongs to a different Runner profile");
        Path file = AgentRunner.stateDirectory(server, stateRoot, userId).resolve("config.json");
        if (!Files.isRegularFile(file))
            throw new IOException("No registered Runner identity here. Start jagent runner first.");
        JsonNode config = RunnerFiles.JSON.readTree(file.toFile());
        runnerId = config.path("runnerId").asText();
        if (!server.equals(config.path("server").asText())
                || runnerId.isEmpty()
                || config.path("secret").asText().isEmpty())
            throw new IOException("Invalid Runner identity");
        http.header("X-Runner-Id", runnerId)
                .header("X-Runner-Secret", config.get("secret").asText());
    }

    static String link(HttpRunnerTransport http, String console) throws IOException {
        String origin = RunnerFiles.serverUrl(console);
        String ticket =
                http.management("POST", "/api/v1/access/browser", Collections.emptyMap())
                        .path("ticket")
                        .asText();
        if (ticket.isEmpty()) throw new IOException("Harness did not issue a browser ticket");
        return origin + "/#jagent-ticket=" + ticket;
    }

    public String link(String console) throws IOException {
        return link(http, console);
    }

    public String pairingBrowser(String code) throws IOException {
        return http.management(
                        "POST", "/api/v1/access/pair/info", Collections.singletonMap("code", code))
                .path("browser")
                .asText();
    }

    public void approve(String code) throws IOException {
        http.management(
                "POST", "/api/v1/access/pair/approve", Collections.singletonMap("code", code));
    }
}
