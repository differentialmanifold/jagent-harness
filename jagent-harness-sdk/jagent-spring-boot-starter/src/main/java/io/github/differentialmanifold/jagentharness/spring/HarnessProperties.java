package io.github.differentialmanifold.jagentharness.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "harness")
public class HarnessProperties {

    private Model model = new Model();
    private Compaction compaction = new Compaction();
    private Prompt prompt = new Prompt();

    public Model getModel() {
        return model;
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    public void setPrompt(Prompt prompt) {
        this.prompt = prompt;
    }

    public Compaction getCompaction() {
        return compaction;
    }

    public void setCompaction(Compaction compaction) {
        this.compaction = compaction;
    }

    public static class Model {
        private String provider = "openai-compatible";
        private String model = "";
        private String baseUrl = "";
        private String apiKey = "";
        private Double temperature;
        private int timeoutSeconds = 120;
        private boolean streamEnabled = true;

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

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public boolean isStreamEnabled() {
            return streamEnabled;
        }

        public void setStreamEnabled(boolean streamEnabled) {
            this.streamEnabled = streamEnabled;
        }
    }

    public static class Prompt {
        private String skillsDir = "skills";
        private String configRoot = "~/.jagent-harness";

        public String getSkillsDir() {
            return skillsDir;
        }

        public void setSkillsDir(String skillsDir) {
            this.skillsDir = skillsDir;
        }

        public String getConfigRoot() {
            return configRoot;
        }

        public void setConfigRoot(String configRoot) {
            this.configRoot = configRoot;
        }
    }

    public static class Compaction {
        private boolean enabled = true;
        private int contextWindowTokens = 128000;
        private double thresholdRatio = 0.8d;
        private int recentMessages = 20;
        private int targetTokens = 4000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getContextWindowTokens() {
            return contextWindowTokens;
        }

        public void setContextWindowTokens(int contextWindowTokens) {
            this.contextWindowTokens = contextWindowTokens;
        }

        public double getThresholdRatio() {
            return thresholdRatio;
        }

        public void setThresholdRatio(double thresholdRatio) {
            this.thresholdRatio = thresholdRatio;
        }

        public int getRecentMessages() {
            return recentMessages;
        }

        public void setRecentMessages(int recentMessages) {
            this.recentMessages = recentMessages;
        }

        public int getTargetTokens() {
            return targetTokens;
        }

        public void setTargetTokens(int targetTokens) {
            this.targetTokens = targetTokens;
        }
    }

}
