package io.github.differentialmanifold.jagentharness.core.agent;

import java.nio.file.Path;

public class AgentSettings {

    private String provider = "openai-compatible";
    private String model = "";
    private Double temperature;
    private Path configRoot;
    private boolean compactionEnabled = true;
    private int contextWindowTokens = 128000;
    private double compactionThresholdRatio = 0.8d;
    private int compactionRecentMessages = 20;
    private int compactionTargetTokens = 4000;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Path getConfigRoot() {
        return configRoot;
    }

    public void setConfigRoot(Path configRoot) {
        this.configRoot = configRoot;
    }

    public boolean isCompactionEnabled() {
        return compactionEnabled;
    }

    public void setCompactionEnabled(boolean compactionEnabled) {
        this.compactionEnabled = compactionEnabled;
    }

    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    public void setContextWindowTokens(int contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
    }

    public double getCompactionThresholdRatio() {
        return compactionThresholdRatio;
    }

    public void setCompactionThresholdRatio(double compactionThresholdRatio) {
        this.compactionThresholdRatio = compactionThresholdRatio;
    }

    public int getCompactionRecentMessages() {
        return compactionRecentMessages;
    }

    public void setCompactionRecentMessages(int compactionRecentMessages) {
        this.compactionRecentMessages = compactionRecentMessages;
    }

    public int getCompactionTargetTokens() {
        return compactionTargetTokens;
    }

    public void setCompactionTargetTokens(int compactionTargetTokens) {
        this.compactionTargetTokens = compactionTargetTokens;
    }
}
