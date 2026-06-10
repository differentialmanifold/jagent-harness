package io.github.differentialmanifold.jagentharness.core.prompt;

import java.util.List;

public interface PromptBindingStore {

    List<PromptBinding> listBindings(String promptName);
}
