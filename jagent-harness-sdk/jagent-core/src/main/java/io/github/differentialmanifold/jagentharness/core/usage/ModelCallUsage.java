package io.github.differentialmanifold.jagentharness.core.usage;

import java.time.Instant;

import io.github.differentialmanifold.jagentharness.core.provider.ModelUsage;
import io.github.differentialmanifold.jagentharness.core.support.Ids;

public class ModelCallUsage {

    public static final String ESTIMATE_SOURCE_FULL = "full_estimate";
    public static final String ESTIMATE_SOURCE_ACTUAL_BASELINE = "actual_baseline_plus_delta";

    private String usageId;
    private String sessionId;
    private String turnId;
    private String messageId;
    private String provider;
    private String model;
    private int contextWindowTokens;
    private int thresholdTokens;
    private String estimateSource;
    private Integer estimatedTokens;
    private Integer actualContextTokens;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer reasoningTokens;
    private Integer cachedTokens;
    private Integer totalTokens;
    private Instant createdAt;

    public static ModelCallUsage fromUsage(String sessionId,
                                           String turnId,
                                           String messageId,
                                           String provider,
                                           String model,
                                           int contextWindowTokens,
                                           int thresholdTokens,
                                           String estimateSource,
                                           Integer estimatedTokens,
                                           ModelUsage usage) {
        ModelCallUsage record = new ModelCallUsage();
        record.setUsageId(Ids.newId("usage"));
        record.setSessionId(sessionId);
        record.setTurnId(turnId);
        record.setMessageId(messageId);
        record.setProvider(provider);
        record.setModel(model);
        record.setContextWindowTokens(contextWindowTokens);
        record.setThresholdTokens(thresholdTokens);
        record.setEstimateSource(estimateSource);
        record.setEstimatedTokens(estimatedTokens);
        if (usage != null) {
            record.setActualContextTokens(usage.getActualContextTokens());
            record.setPromptTokens(usage.getPromptTokens());
            record.setCompletionTokens(usage.getCompletionTokens());
            record.setReasoningTokens(usage.getReasoningTokens());
            record.setCachedTokens(usage.getCachedTokens());
            record.setTotalTokens(usage.getTotalTokens());
        }
        record.setCreatedAt(Instant.now());
        return record;
    }

    public String getUsageId() {
        return usageId;
    }

    public void setUsageId(String usageId) {
        this.usageId = usageId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

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

    public int getContextWindowTokens() {
        return contextWindowTokens;
    }

    public void setContextWindowTokens(int contextWindowTokens) {
        this.contextWindowTokens = contextWindowTokens;
    }

    public int getThresholdTokens() {
        return thresholdTokens;
    }

    public void setThresholdTokens(int thresholdTokens) {
        this.thresholdTokens = thresholdTokens;
    }

    public String getEstimateSource() {
        return estimateSource;
    }

    public void setEstimateSource(String estimateSource) {
        this.estimateSource = estimateSource;
    }

    public Integer getEstimatedTokens() {
        return estimatedTokens;
    }

    public void setEstimatedTokens(Integer estimatedTokens) {
        this.estimatedTokens = estimatedTokens;
    }

    public Integer getActualContextTokens() {
        return actualContextTokens;
    }

    public void setActualContextTokens(Integer actualContextTokens) {
        this.actualContextTokens = actualContextTokens;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getReasoningTokens() {
        return reasoningTokens;
    }

    public void setReasoningTokens(Integer reasoningTokens) {
        this.reasoningTokens = reasoningTokens;
    }

    public Integer getCachedTokens() {
        return cachedTokens;
    }

    public void setCachedTokens(Integer cachedTokens) {
        this.cachedTokens = cachedTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
