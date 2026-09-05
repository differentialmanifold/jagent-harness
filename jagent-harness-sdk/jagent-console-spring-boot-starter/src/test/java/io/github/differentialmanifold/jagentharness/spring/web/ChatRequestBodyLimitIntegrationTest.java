package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = ChatRequestBodyLimitIntegrationTest.TestApplication.class,
        properties = {
                "harness.console.enabled=false",
                "harness.console.max-chat-request-body-size=128B",
                "spring.autoconfigure.exclude="
                        + "io.github.differentialmanifold.jagentharness.spring.AgentHarnessAutoConfiguration,"
                        + "io.github.differentialmanifold.jagentharness.mcp.spring.McpAutoConfiguration,"
                        + "io.github.differentialmanifold.jagentharness.spring.web.AgentConsoleWebConfiguration"
        })
class ChatRequestBodyLimitIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void rejectsChunkedStreamRequestWithNoContentLength() throws Exception {
        Response response = chunkedPost("/api/chat/stream", oversizedJson());

        assertPayloadTooLarge(response);
    }

    @Test
    void rejectsChunkedRunningMessageWithNoContentLength() throws Exception {
        Response response = chunkedPost("/api/chat/runs/run-1/messages", oversizedJson());

        assertPayloadTooLarge(response);
    }

    @Test
    void preservesTheSseResponseForAnAcceptedStreamRequest() throws Exception {
        Response response = chunkedPost("/api/chat/stream", "{\"content\":\"small\"}");

        assertEquals(200, response.status);
        assertTrue(response.contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
        assertEquals("data: ok\n\n", response.body);
    }

    @Test
    void doesNotApplyTheLimitToOtherChatRequests() throws Exception {
        Response response = chunkedPost("/api/chat/runs/run-1/stop", oversizedJson());

        assertEquals(200, response.status);
        assertEquals("stopped", response.body);
    }

    private void assertPayloadTooLarge(Response response) throws IOException {
        assertEquals(413, response.status);
        JsonNode error = objectMapper.readTree(response.body);
        assertEquals(413, error.path("status").asInt());
        assertEquals("Payload Too Large", error.path("error").asText());
    }

    private Response chunkedPost(String path, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        connection.setChunkedStreamingMode(16);
        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        connection.getOutputStream().close();

        int status = connection.getResponseCode();
        InputStream responseStream = status >= 400
                ? connection.getErrorStream()
                : connection.getInputStream();
        String responseBody = responseStream == null ? "" : readString(responseStream);
        String contentType = connection.getContentType() == null ? "" : connection.getContentType();
        connection.disconnect();
        return new Response(status, contentType, responseBody);
    }

    private String oversizedJson() {
        StringBuilder body = new StringBuilder("{\"content\":\"");
        while (body.length() < 256) {
            body.append('x');
        }
        return body.append("\"}").toString();
    }

    private String readString(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(ConsoleProperties.class)
    @Import({TestController.class, AgentConsoleExceptionHandler.class})
    static class TestApplication {

        @Bean
        FilterRegistrationBean<ChatRequestBodyLimitFilter> chatRequestBodyLimitFilterRegistration(
                ConsoleProperties properties,
                ObjectMapper objectMapper) {
            ChatRequestBodyLimitFilter filter = new ChatRequestBodyLimitFilter(
                    properties.getMaxChatRequestBodySize().toBytes(),
                    objectMapper);
            FilterRegistrationBean<ChatRequestBodyLimitFilter> registration =
                    new FilterRegistrationBean<ChatRequestBodyLimitFilter>(filter);
            registration.addUrlPatterns("/api/chat/*");
            return registration;
        }
    }

    @RestController
    static class TestController {

        @PostMapping(path = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        ResponseEntity<String> stream(@RequestBody Map<String, Object> request) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body("data: ok\n\n");
        }

        @PostMapping("/api/chat/runs/{runId}/messages")
        Map<String, Object> message(@PathVariable("runId") String runId,
                                    @RequestBody Map<String, Object> request) {
            return request;
        }

        @PostMapping("/api/chat/runs/{runId}/stop")
        String stop(@PathVariable("runId") String runId,
                    @RequestBody(required = false) String request) {
            return "stopped";
        }
    }

    private static final class Response {

        private final int status;
        private final String contentType;
        private final String body;

        private Response(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
