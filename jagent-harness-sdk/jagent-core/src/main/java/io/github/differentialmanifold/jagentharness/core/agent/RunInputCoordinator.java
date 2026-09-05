package io.github.differentialmanifold.jagentharness.core.agent;

import java.util.List;

import io.github.differentialmanifold.jagentharness.core.message.MessageImage;

public interface RunInputCoordinator extends RunInputSource {

    void activateRun(String sessionId, String runId);

    RunInputReceipt submitInput(String runId, String content, String inputId);

    default RunInputReceipt submitInput(String runId,
                                        String content,
                                        List<MessageImage> images,
                                        String inputId) {
        if (images == null || images.isEmpty()) {
            return submitInput(runId, content, inputId);
        }
        throw new UnsupportedOperationException("This RunInputCoordinator does not support image input");
    }

    void closeRun(String sessionId, String runId);
}
