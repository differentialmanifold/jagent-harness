package io.github.differentialmanifold.jagentharness.core.agent;

public class AgentRunResult {

    private String status = "COMPLETED";
    private java.util.List<io.github.differentialmanifold.jagentharness.core.tool.ToolCall>
            toolCalls = new java.util.ArrayList<>();

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public java.util.List<io.github.differentialmanifold.jagentharness.core.tool.ToolCall>
            getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(
            java.util.List<io.github.differentialmanifold.jagentharness.core.tool.ToolCall> calls) {
        this.toolCalls = new java.util.ArrayList<>(calls);
    }

    private String sessionId;
    private String runId;
    private String firstTurnId;
    private String lastTurnId;
    private java.util.List<String> attachmentIds = new java.util.ArrayList<>();

    public java.util.List<String> getAttachmentIds() {
        return attachmentIds;
    }

    public void setAttachmentIds(java.util.List<String> ids) {
        attachmentIds = ids;
    }

    private String answer;
    private int turnCount;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getFirstTurnId() {
        return firstTurnId;
    }

    public void setFirstTurnId(String firstTurnId) {
        this.firstTurnId = firstTurnId;
    }

    public String getLastTurnId() {
        return lastTurnId;
    }

    public void setLastTurnId(String lastTurnId) {
        this.lastTurnId = lastTurnId;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public int getTurnCount() {
        return turnCount;
    }

    public void setTurnCount(int turnCount) {
        this.turnCount = turnCount;
    }
}
