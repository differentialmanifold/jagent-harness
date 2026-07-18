package io.github.differentialmanifold.jagentharness.core.agent;

public class AgentRunResult {

    private String sessionId;
    private String runId;
    private String firstTurnId;
    private String lastTurnId;
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
