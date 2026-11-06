package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.differentialmanifold.jagentharness.core.agent.RunInputCoordinator;
import io.github.differentialmanifold.jagentharness.core.execution.*;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.protocol.ChatRequest;
import io.github.differentialmanifold.jagentharness.protocol.ChatResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ChatControllerTest {
    @Test
    void streamsStartBeyondTheFormerTaskLimitWithoutWaitingForAnotherSession() throws Exception {
        int count = 40;
        CountDownLatch entered = new CountDownLatch(count);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(count);
        ConversationService service = mock(ConversationService.class);
        when(service.chat(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            entered.countDown();
                            try {
                                release.await();
                                return new ChatResponse();
                            } finally {
                                finished.countDown();
                            }
                        });
        RunService runs =
                new RunService(
                        service, null, mock(SessionManager.class), mock(RunInputCoordinator.class));
        ChatController controller = new ChatController(runs);
        try {
            for (int i = 0; i < count; i++) {
                ChatRequest request = new ChatRequest();
                request.sessionId = "session_" + i;
                request.messages.add(
                        io.github.differentialmanifold.jagentharness.protocol.ChatMessage.user(
                                "work"));
                assertNotNull(controller.stream(request));
            }
            assertTrue(
                    entered.await(10, TimeUnit.SECONDS),
                    "All 40 requests must start while the previous requests are still running");
            assertEquals(count, finished.getCount());
        } finally {
            release.countDown();
            try {
                assertTrue(finished.await(10, TimeUnit.SECONDS));
            } finally {
                controller.close();
                runs.close();
            }
        }
    }
}
