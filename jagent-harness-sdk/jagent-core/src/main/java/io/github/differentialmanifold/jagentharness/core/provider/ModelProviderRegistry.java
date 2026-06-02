package io.github.differentialmanifold.jagentharness.core.provider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ModelProviderRegistry {

    private final Map<String, ModelProvider> providers = new LinkedHashMap<String, ModelProvider>();

    public ModelProviderRegistry() {
    }

    public ModelProviderRegistry(List<ModelProvider> providers) {
        if (providers != null) {
            for (ModelProvider provider : providers) {
                register(provider);
            }
        }
    }

    public synchronized void register(ModelProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (providers.containsKey(provider.getName())) {
            throw new IllegalArgumentException("Provider already registered: " + provider.getName());
        }
        providers.put(provider.getName(), provider);
    }

    public synchronized ModelProvider get(String name) {
        return providers.get(name);
    }

    public synchronized Collection<ModelProvider> all() {
        return Collections.unmodifiableList(new ArrayList<ModelProvider>(providers.values()));
    }
}
