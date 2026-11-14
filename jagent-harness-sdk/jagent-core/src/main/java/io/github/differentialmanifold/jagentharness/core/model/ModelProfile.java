package io.github.differentialmanifold.jagentharness.core.model;

import java.util.*;

/** A configured model, independent of Agent identity and Runner environment. */
public class ModelProfile {
    public String id, name, model, description = "";
    public String provider = "openai-compatible", baseUrl = "", apiKey = "";
    public List<String> inputTypes = new ArrayList<>(Collections.singletonList("text"));
    public List<String> capabilities = new ArrayList<>();
    public int contextWindowTokens = 128000;
    public Double temperature;
    public int timeoutSeconds = 120;
    public boolean streamEnabled = true, includeUsage = true;
    public boolean enabled = true;
    public boolean credentialsConfigured;

    public ModelProfile copy() {
        ModelProfile p = new ModelProfile();
        p.id = id;
        p.name = name;
        p.model = model;
        p.description = description;
        p.provider = provider;
        p.baseUrl = baseUrl;
        p.apiKey = apiKey;
        p.inputTypes = new ArrayList<>(inputTypes);
        p.capabilities = new ArrayList<>(capabilities);
        p.contextWindowTokens = contextWindowTokens;
        p.enabled = enabled;
        p.temperature = temperature;
        p.timeoutSeconds = timeoutSeconds;
        p.streamEnabled = streamEnabled;
        p.includeUsage = includeUsage;
        p.credentialsConfigured = credentialsConfigured || (apiKey != null && !apiKey.isEmpty());
        return p;
    }

    public ModelProfile publicView() {
        ModelProfile p = copy();
        p.apiKey = "";
        return p;
    }

    public Map<String, Object> description() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("description", description);
        out.put("inputTypes", inputTypes);
        out.put("capabilities", capabilities);
        out.put("contextWindowTokens", contextWindowTokens);
        return out;
    }
}
