package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.differentialmanifold.jagentharness.core.agent.RunStopCoordinator;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopHandle;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ChatControllerTest {

    @Test
    void returnsAcceptedWhenStopWasRecorded() {
        ChatController controller = controller(StopRequestResult.REQUESTED);

        assertEquals(HttpStatus.ACCEPTED, controller.stop("request-1").getStatusCode());
    }

    @Test
    void returnsNotFoundWhenRequestIsNotActive() {
        ChatController controller = controller(StopRequestResult.NOT_FOUND);

        assertEquals(HttpStatus.NOT_FOUND, controller.stop("request-1").getStatusCode());
    }

    private ChatController controller(StopRequestResult result) {
        RunStopCoordinator coordinator = new RunStopCoordinator() {
            @Override
            public RunStopHandle register(String requestId, String sessionId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StopRequestResult requestStop(String requestId) {
                return result;
            }
        };
        return new ChatController(null, null, null, coordinator);
    }
}
