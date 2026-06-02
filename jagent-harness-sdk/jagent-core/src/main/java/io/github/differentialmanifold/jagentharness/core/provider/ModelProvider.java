package io.github.differentialmanifold.jagentharness.core.provider;

import java.util.function.Consumer;

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
}
