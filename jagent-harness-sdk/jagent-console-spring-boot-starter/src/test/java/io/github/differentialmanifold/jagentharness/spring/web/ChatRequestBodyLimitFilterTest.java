package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.ServletInputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ChatRequestBodyLimitFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void limitsUnknownLengthStreamRequestWhileItIsRead() throws Exception {
        assertUnknownLengthRequestRejected("/api/chat/stream");
    }

    @Test
    void limitsUnknownLengthRunningMessageWhileItIsRead() throws Exception {
        assertUnknownLengthRequestRejected("/api/chat/runs/run-1/messages");
    }

    @Test
    void rejectsKnownOversizedBodyBeforeInvokingTheControllerChain() throws Exception {
        ChatRequestBodyLimitFilter filter = new ChatRequestBodyLimitFilter(8L, objectMapper);
        MockHttpServletRequest request = request("/api/chat/stream", "012345678");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertEquals(413, response.getStatus());
        assertFalse(invoked.get());
        assertPayloadTooLarge(response);
    }

    @Test
    void permitsARequestWhoseUnknownLengthBodyExactlyMatchesTheLimit() throws Exception {
        ChatRequestBodyLimitFilter filter = new ChatRequestBodyLimitFilter(8L, objectMapper);
        MockHttpServletRequest request = unknownLengthRequest("/api/chat/stream", "01234567");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ByteArrayOutputStream received = new ByteArrayOutputStream();

        filter.doFilter(request, response, (limitedRequest, ignoredResponse) -> {
            ServletInputStream input = limitedRequest.getInputStream();
            int value;
            while ((value = input.read()) >= 0) {
                received.write(value);
            }
        });

        assertEquals(200, response.getStatus());
        assertEquals("01234567", new String(received.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void doesNotLimitOtherChatActions() throws Exception {
        ChatRequestBodyLimitFilter filter = new ChatRequestBodyLimitFilter(8L, objectMapper);
        MockHttpServletRequest request = request("/api/chat/runs/run-1/stop", "012345678");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertEquals(200, response.getStatus());
        assertEquals(true, invoked.get());
    }

    private void assertUnknownLengthRequestRejected(String path) throws Exception {
        ChatRequestBodyLimitFilter filter = new ChatRequestBodyLimitFilter(8L, objectMapper);
        MockHttpServletRequest request = unknownLengthRequest(path, "012345678");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (limitedRequest, ignoredResponse) -> {
            byte[] buffer = new byte[32];
            while (limitedRequest.getInputStream().read(buffer) >= 0) {
                // Consume the request as an HTTP message converter would.
            }
        });

        assertEquals(413, response.getStatus());
        assertPayloadTooLarge(response);
    }

    private void assertPayloadTooLarge(MockHttpServletResponse response) throws IOException {
        JsonNode error = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(413, error.path("status").asInt());
        assertEquals("Payload Too Large", error.path("error").asText());
    }

    private MockHttpServletRequest request(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private MockHttpServletRequest unknownLengthRequest(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
