package io.github.differentialmanifold.jagentharness.core.provider;

import java.util.function.Consumer;

public interface ModelDeltaConsumer {

    default void onContentDelta(String delta) {
    }

    default void onReasoningDelta(String delta) {
    }

    static ModelDeltaConsumer contentOnly(Consumer<String> contentDeltaConsumer) {
        if (contentDeltaConsumer == null) {
            return null;
        }
        return new ModelDeltaConsumer() {
            @Override
            public void onContentDelta(String delta) {
                contentDeltaConsumer.accept(delta);
            }
        };
    }
}
