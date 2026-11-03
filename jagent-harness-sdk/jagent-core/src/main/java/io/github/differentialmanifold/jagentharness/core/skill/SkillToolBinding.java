package io.github.differentialmanifold.jagentharness.core.skill;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class SkillToolBinding {
    private final String server;
    private final String tool;
    private final String modelName;

    @JsonCreator
    public SkillToolBinding(
            @JsonProperty("server") String server,
            @JsonProperty("tool") String tool,
            @JsonProperty("modelName") String modelName) {
        this.server = server;
        this.tool = tool;
        this.modelName = modelName;
    }

    public String getServer() {
        return server;
    }

    public String getTool() {
        return tool;
    }

    public String getModelName() {
        return modelName;
    }
}
