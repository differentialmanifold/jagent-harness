package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.github.differentialmanifold.jagentharness.core.agent.RunStopCoordinator;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopHandle;
import io.github.differentialmanifold.jagentharness.core.agent.StopRegistration;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestResult;
import io.github.differentialmanifold.jagentharness.core.session.SessionDetails;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatRunRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatStopRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatControllerTest {

    @Test
    void generatesRequestIdAndReturnsItInStreamResponseHeader() {
        RecordingCoordinator coordinator = new RecordingCoordinator(StopRequestResult.REQUESTED);
        ChatController controller = new ChatController(
                sessionManager(),
                null,
                task -> {
                },
                coordinator);
        ChatRunRequest request = new ChatRunRequest();
        request.setSessionId("session-1");
        request.setContent("hello");

        ResponseEntity<SseEmitter> response = controller.stream(request);

        String requestId = response.getHeaders().getFirst(ChatController.REQUEST_ID_HEADER);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(requestId);
        assertTrue(requestId.matches("req_[0-9a-f]{32}"));
        assertEquals(requestId, coordinator.registeredRequestId);
        assertEquals("session-1", coordinator.registeredSessionId);
    }

    @Test
    void returnsAcceptedWhenStopWasRecorded() {
        ChatController controller = controller(StopRequestResult.REQUESTED);

        assertEquals(HttpStatus.ACCEPTED, controller.stop(stopRequest("request-1")).getStatusCode());
    }

    @Test
    void returnsNotFoundWhenRequestIsNotActive() {
        ChatController controller = controller(StopRequestResult.NOT_FOUND);

        assertEquals(HttpStatus.NOT_FOUND, controller.stop(stopRequest("request-1")).getStatusCode());
    }

    @Test
    void rejectsMissingOrOversizedRequestId() {
        ChatController controller = controller(StopRequestResult.REQUESTED);

        assertThrows(IllegalArgumentException.class, () -> controller.stop(stopRequest(" ")));
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.stop(stopRequest(repeat("x", 129))));
    }

    private ChatController controller(StopRequestResult result) {
        return new ChatController(null, null, null, new RecordingCoordinator(result));
    }

    private ChatStopRequest stopRequest(String requestId) {
        ChatStopRequest request = new ChatStopRequest();
        request.setRequestId(requestId);
        return request;
    }

    private String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }

    private SessionManager sessionManager() {
        return new SessionManager() {
            @Override
            public SessionRecord createSession(String title, String workspacePath) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SessionRecord requireSession(String sessionId) {
                return null;
            }

            @Override
            public List<SessionRecord> listSessions() {
                throw new UnsupportedOperationException();
            }

            @Override
            public SessionDetails getDetails(String sessionId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public SessionRecord renameSession(String sessionId, String title) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deleteSession(String sessionId) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static class RecordingCoordinator implements RunStopCoordinator {

        private final StopRequestResult stopResult;
        private String registeredRequestId;
        private String registeredSessionId;

        private RecordingCoordinator(StopRequestResult stopResult) {
            this.stopResult = stopResult;
        }

        @Override
        public RunStopHandle register(String requestId, String sessionId) {
            registeredRequestId = requestId;
            registeredSessionId = sessionId;
            return new RunStopHandle() {
                @Override
                public String getRequestId() {
                    return requestId;
                }

                @Override
                public String getSessionId() {
                    return sessionId;
                }

                @Override
                public boolean isAborted() {
                    return false;
                }

                @Override
                public void throwIfAborted() {
                }

                @Override
                public StopRegistration onStop(Runnable action) {
                    return () -> {
                    };
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public StopRequestResult requestStop(String requestId) {
            return stopResult;
        }
    }
}
