package io.github.differentialmanifold.jagentharness.core.provider;

public class ModelUsage {

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer reasoningTokens;
    private Integer cachedTokens;
    private Integer totalTokens;

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

    /**
     * Returns the provider-reported baseline for the next model call. Reasoning tokens are excluded
     * because reasoning content is not replayed in conversation history.
     */
    public Integer getActualContextTokens() {
        if (totalTokens == null) {
            return null;
        }
        int reasoning = reasoningTokens == null ? 0 : reasoningTokens;
        return Math.max(0, totalTokens - reasoning);
    }

    public boolean hasTokenCounts() {
        return promptTokens != null
                || completionTokens != null
                || totalTokens != null
                || reasoningTokens != null
                || cachedTokens != null;
    }
}
