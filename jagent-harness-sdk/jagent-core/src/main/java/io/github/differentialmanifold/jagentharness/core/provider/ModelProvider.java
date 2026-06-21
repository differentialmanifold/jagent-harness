package io.github.differentialmanifold.jagentharness.core.provider;

import java.util.function.Consumer;

import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;

public interface ModelProvider {

    String getName();

    ModelResponse chat(ModelRequest request);

    default ModelResponse chat(ModelRequest request, Consumer<String> contentDeltaConsumer) {
        ModelResponse response = chat(request);
        if (contentDeltaConsumer != null && response.getContent() != null) {
            contentDeltaConsumer.accept(response.getContent());
        }
        return response;
    }

    default ModelResponse chat(ModelRequest request, ModelDeltaConsumer deltaConsumer) {
        Consumer<String> contentDeltaConsumer = deltaConsumer == null ? null : deltaConsumer::onContentDelta;
        ModelResponse response = chat(request, contentDeltaConsumer);
        if (deltaConsumer != null
                && response != null
                && response.getReasoningContent() != null
                && !response.getReasoningContent().isEmpty()) {
            deltaConsumer.onReasoningDelta(response.getReasoningContent());
        }
        return response;
    }

    default ModelResponse chat(ModelRequest request,
                               Consumer<String> contentDeltaConsumer,
                               StopSignal stopSignal) {
        StopSignal effectiveSignal = stopSignal == null ? StopSignal.none() : stopSignal;
        effectiveSignal.throwIfAborted();
        ModelResponse response = chat(request, contentDeltaConsumer);
        effectiveSignal.throwIfAborted();
        return response;
    }

    default ModelResponse chat(ModelRequest request,
                               ModelDeltaConsumer deltaConsumer,
                               StopSignal stopSignal) {
        StopSignal effectiveSignal = stopSignal == null ? StopSignal.none() : stopSignal;
        effectiveSignal.throwIfAborted();
        ModelResponse response = chat(request, deltaConsumer);
        effectiveSignal.throwIfAborted();
        return response;
    }
}
