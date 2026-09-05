package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentHarness;
import io.github.differentialmanifold.jagentharness.core.agent.AgentRunOptions;
import io.github.differentialmanifold.jagentharness.core.agent.AgentRunResult;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopCoordinator;
import io.github.differentialmanifold.jagentharness.core.agent.RunInput;
import io.github.differentialmanifold.jagentharness.core.agent.RunInputCoordinator;
import io.github.differentialmanifold.jagentharness.core.agent.RunInputReceipt;
import io.github.differentialmanifold.jagentharness.core.agent.RunInputStatus;
import io.github.differentialmanifold.jagentharness.core.agent.RunStopHandle;
import io.github.differentialmanifold.jagentharness.core.agent.StopRegistration;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestResult;
import io.github.differentialmanifold.jagentharness.core.agent.StopRequestedException;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
import io.github.differentialmanifold.jagentharness.core.session.SessionDetails;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalCoordinator;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalDecision;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatRunRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatImageRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatInputRequest;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ChatInputResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatControllerTest {

    @Test
    void generatesRunIdAndReturnsOnlyItInStreamResponseHeader() {
        RecordingCoordinator coordinator = new RecordingCoordinator(StopRequestResult.REQUESTED);
        RecordingInputCoordinator inputCoordinator = new RecordingInputCoordinator();
        ChatController controller = new ChatController(
                sessionManager(),
                null,
                task -> {
                },
                coordinator,
                inputCoordinator,
                new NoopToolApprovalCoordinator(),
                new ObjectMapper());
        ChatRunRequest request = new ChatRunRequest();
        request.setSessionId("session-1");
        request.setContent("hello");

        ResponseEntity<SseEmitter> response = controller.stream(request);

        String runId = response.getHeaders().getFirst(ChatController.RUN_ID_HEADER);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(Collections.singleton(ChatController.RUN_ID_HEADER), response.getHeaders().keySet());
        assertNotNull(runId);
        assertTrue(runId.matches("run_[0-9a-f]{32}"));
        assertEquals(runId, coordinator.registeredRunId);
        assertEquals("session-1", coordinator.registeredSessionId);
        assertEquals("session-1", inputCoordinator.activatedSessionId);
        assertEquals(runId, inputCoordinator.activatedRunId);
    }

    @Test
    void returnsAcceptedWhenStopWasRecorded() {
        ChatController controller = controller(StopRequestResult.REQUESTED);

        assertEquals(HttpStatus.ACCEPTED, controller.stop("run-1").getStatusCode());
    }

    @Test
    void returnsNotFoundWhenRunIsNotActive() {
        ChatController controller = controller(StopRequestResult.NOT_FOUND);

        assertEquals(HttpStatus.NOT_FOUND, controller.stop("run-1").getStatusCode());
    }

    @Test
    void rejectsMissingOrOversizedRunId() {
        ChatController controller = controller(StopRequestResult.REQUESTED);

        assertThrows(IllegalArgumentException.class, () -> controller.stop(" "));
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.stop(repeat("x", 129)));
    }

    @Test
    void invokesAgentHarnessOnceWithTheActiveRunInputSource() {
        RecordingInputCoordinator inputCoordinator = new RecordingInputCoordinator();
        RecordingAgentHarness agentHarness = new RecordingAgentHarness();
        ChatController controller = new ChatController(
                sessionManager(),
                agentHarness,
                Runnable::run,
                new RecordingCoordinator(StopRequestResult.REQUESTED),
                inputCoordinator,
                new NoopToolApprovalCoordinator(),
                new ObjectMapper());
        ChatRunRequest request = new ChatRunRequest();
        request.setSessionId("session-1");
        request.setContent("initial");

        ResponseEntity<SseEmitter> response = controller.stream(request);
        String initialRunId = response.getHeaders().getFirst(ChatController.RUN_ID_HEADER);

        assertEquals(Collections.singletonList(initialRunId), agentHarness.runIds);
        assertEquals(Collections.singletonList("initial"), agentHarness.contents);
        assertTrue(agentHarness.usedInputCoordinator);
        assertEquals(Collections.singletonList(initialRunId), inputCoordinator.closedRunIds);
    }

    @Test
    void acceptsImageOnlyRunsAndPassesValidatedImagesToTheAgent() {
        RecordingAgentHarness agentHarness = new RecordingAgentHarness();
        ChatController controller = new ChatController(
                sessionManager(),
                agentHarness,
                Runnable::run,
                new RecordingCoordinator(StopRequestResult.REQUESTED),
                new RecordingInputCoordinator(),
                new NoopToolApprovalCoordinator(),
                new ObjectMapper());
        ChatRunRequest request = new ChatRunRequest();
        request.setSessionId("session-1");
        request.setImages(Collections.singletonList(image(
                "screen.png", "image/png", "data:image/png;base64,iVBORw0KGgo=")));

        controller.stream(request);

        assertEquals(Collections.singletonList(""), agentHarness.contents);
        assertEquals(1, agentHarness.images.get(0).size());
        assertEquals("screen.png", agentHarness.images.get(0).get(0).getName());
        assertEquals("image/png", agentHarness.images.get(0).get(0).getMediaType());
    }

    @Test
    void closesCurrentRunWhenAgentStops() {
        RecordingInputCoordinator inputCoordinator = new RecordingInputCoordinator();
        RecordingAgentHarness agentHarness = new RecordingAgentHarness();
        agentHarness.failure = new StopRequestedException();
        ChatController controller = new ChatController(
                sessionManager(),
                agentHarness,
                Runnable::run,
                new RecordingCoordinator(StopRequestResult.REQUESTED),
                inputCoordinator,
                new NoopToolApprovalCoordinator(),
                new ObjectMapper());
        ChatRunRequest request = new ChatRunRequest();
        request.setSessionId("session-1");
        request.setContent("initial");

        ResponseEntity<SseEmitter> response = controller.stream(request);
        String initialRunId = response.getHeaders().getFirst(ChatController.RUN_ID_HEADER);

        assertEquals(Collections.singletonList(initialRunId), agentHarness.runIds);
        assertEquals(Collections.singletonList(initialRunId), inputCoordinator.closedRunIds);
    }

    @Test
    void returnsAcceptedBestEffortReceiptForRunningMessage() {
        RecordingInputCoordinator inputCoordinator = new RecordingInputCoordinator();
        ChatController controller = new ChatController(
                sessionManager(),
                null,
                null,
                new RecordingCoordinator(StopRequestResult.REQUESTED),
                inputCoordinator,
                new NoopToolApprovalCoordinator(),
                new ObjectMapper());
        ChatInputRequest message = inputRequest("input-1", "  change direction  ");

        ResponseEntity<ChatInputResponse> response = controller.submitMessage("run-1", message);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("input-1", response.getBody().getInputId());
        assertEquals("ACCEPTED", response.getBody().getStatus());
        assertEquals("run-1", inputCoordinator.submittedRunId);
        assertEquals("change direction", inputCoordinator.submittedContent);
    }

    @Test
    void acceptsImageOnlyRunningMessages() {
        RecordingInputCoordinator inputCoordinator = new RecordingInputCoordinator();
        ChatController controller = new ChatController(
                sessionManager(),
                null,
                null,
                new RecordingCoordinator(StopRequestResult.REQUESTED),
                inputCoordinator,
                new NoopToolApprovalCoordinator(),
                new ObjectMapper());
        ChatInputRequest request = inputRequest("input-image", null);
        request.setImages(Collections.singletonList(image(
                null, null, "data:image/jpeg;base64,/9j/")));

        ResponseEntity<ChatInputResponse> response = controller.submitMessage("run-1", request);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("", inputCoordinator.submittedContent);
        assertEquals(1, inputCoordinator.submittedImages.size());
        assertEquals("image-1.jpg", inputCoordinator.submittedImages.get(0).getName());
    }

    @Test
    void acceptsGifAndWebpMagicBytes() {
        RecordingInputCoordinator inputCoordinator = new RecordingInputCoordinator();
        ChatController controller = new ChatController(
                sessionManager(),
                null,
                null,
                new RecordingCoordinator(StopRequestResult.REQUESTED),
                inputCoordinator,
                new NoopToolApprovalCoordinator(),
                new ObjectMapper());
        ChatInputRequest request = inputRequest("input-images", null);
        request.setImages(Arrays.asList(
                image("animation.gif", "image/gif", "data:image/gif;base64,R0lGODlh"),
                image("photo.webp", "image/webp", "DATA:image/webp;BASE64,UklGRgAAAABXRUJQ")));

        controller.submitMessage("run-1", request);

        assertEquals(2, inputCoordinator.submittedImages.size());
        assertEquals("image/gif", inputCoordinator.submittedImages.get(0).getMediaType());
        assertEquals("image/webp", inputCoordinator.submittedImages.get(1).getMediaType());
    }

    @Test
    void rejectsUnsupportedOrMalformedImageData() {
        ChatController controller = controller(StopRequestResult.REQUESTED);
        ChatInputRequest unsupported = inputRequest("input-1", null);
        unsupported.setImages(Collections.singletonList(image(
                "vector.svg", "image/svg+xml", "data:image/svg+xml;base64,PHN2Zz4=")));
        ChatInputRequest malformed = inputRequest("input-2", null);
        malformed.setImages(Collections.singletonList(image(
                "screen.png", "image/png", "data:image/png;base64,not base64")));
        ChatInputRequest mismatchedSignature = inputRequest("input-3", null);
        mismatchedSignature.setImages(Collections.singletonList(image(
                "screen.png", "image/png", "data:image/png;base64,/9j/")));

        assertThrows(IllegalArgumentException.class,
                () -> controller.submitMessage("run-1", unsupported));
        assertThrows(IllegalArgumentException.class,
                () -> controller.submitMessage("run-1", malformed));
        assertThrows(IllegalArgumentException.class,
                () -> controller.submitMessage("run-1", mismatchedSignature));
    }

    private ChatImageRequest image(String name, String mediaType, String url) {
        ChatImageRequest image = new ChatImageRequest();
        image.setName(name);
        image.setMediaType(mediaType);
        image.setUrl(url);
        return image;
    }

    private ChatInputRequest inputRequest(String inputId, String content) {
        ChatInputRequest request = new ChatInputRequest();
        request.setInputId(inputId);
        request.setContent(content);
        return request;
    }

    private ChatController controller(StopRequestResult result) {
        return new ChatController(
                null,
                null,
                null,
                new RecordingCoordinator(result),
                new RecordingInputCoordinator(),
                new NoopToolApprovalCoordinator(),
                new ObjectMapper());
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
        private String registeredRunId;
        private String registeredSessionId;

        private RecordingCoordinator(StopRequestResult stopResult) {
            this.stopResult = stopResult;
        }

        @Override
        public RunStopHandle register(String runId, String sessionId) {
            registeredRunId = runId;
            registeredSessionId = sessionId;
            return new RunStopHandle() {
                @Override
                public String getRunId() {
                    return runId;
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
        public StopRequestResult requestStop(String runId) {
            return stopResult;
        }
    }

    private static class RecordingInputCoordinator implements RunInputCoordinator {

        private String activatedSessionId;
        private String activatedRunId;
        private final List<String> closedRunIds = new ArrayList<String>();
        private String submittedRunId;
        private String submittedContent;
        private List<MessageImage> submittedImages = Collections.emptyList();

        @Override
        public void activateRun(String sessionId, String runId) {
            activatedSessionId = sessionId;
            activatedRunId = runId;
        }

        @Override
        public RunInputReceipt submitInput(String runId, String content, String inputId) {
            submittedRunId = runId;
            submittedContent = content;
            return new RunInputReceipt(inputId, RunInputStatus.ACCEPTED);
        }

        @Override
        public RunInputReceipt submitInput(String runId,
                                           String content,
                                           List<MessageImage> images,
                                           String inputId) {
            submittedRunId = runId;
            submittedContent = content;
            submittedImages = images;
            return new RunInputReceipt(inputId, RunInputStatus.ACCEPTED);
        }

        @Override
        public List<RunInput> claimPendingInputs(String sessionId,
                                                String runId,
                                                String completedTurnId) {
            return Collections.emptyList();
        }

        @Override
        public void closeRun(String sessionId, String runId) {
            closedRunIds.add(runId);
        }
    }

    private static class RecordingAgentHarness implements AgentHarness {

        private final List<String> runIds = new ArrayList<String>();
        private final List<String> contents = new ArrayList<String>();
        private final List<List<MessageImage>> images = new ArrayList<List<MessageImage>>();
        private RuntimeException failure;
        private boolean usedInputCoordinator = true;

        @Override
        public AgentRunResult run(String sessionId, String userText) {
            return run(sessionId, userText, AgentRunOptions.empty());
        }

        @Override
        public AgentRunResult run(String sessionId, String userText, AgentRunOptions options) {
            return run(sessionId, userText, Collections.<MessageImage>emptyList(), options);
        }

        @Override
        public AgentRunResult run(String sessionId,
                                  String userText,
                                  List<MessageImage> messageImages,
                                  AgentRunOptions options) {
            runIds.add(options.getRunId());
            contents.add(userText);
            images.add(messageImages);
            usedInputCoordinator &= options.getRunInputSource() instanceof RunInputCoordinator;
            if (failure != null) {
                throw failure;
            }
            AgentRunResult result = new AgentRunResult();
            result.setSessionId(sessionId);
            result.setRunId(options.getRunId());
            result.setAnswer("done");
            return result;
        }
    }

    private static class NoopToolApprovalCoordinator implements ToolApprovalCoordinator {

        @Override
        public ToolApprovalDecision awaitDecision(String runId,
                                                  String sessionId,
                                                  ToolApprovalRequest request,
                                                  StopSignal stopSignal,
                                                  Runnable onPending) {
            if (onPending != null) {
                onPending.run();
            }
            return ToolApprovalDecision.approved();
        }

        @Override
        public boolean resolve(String runId, String approvalId, boolean approved, String reason) {
            return false;
        }

        @Override
        public void cancelRun(String runId) {
        }
    }
}
