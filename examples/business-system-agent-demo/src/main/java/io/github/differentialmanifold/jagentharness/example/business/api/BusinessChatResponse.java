package io.github.differentialmanifold.jagentharness.example.business.api;

public class BusinessChatResponse {

    private String sessionId;
    private String turnId;
    private String answer;
    private int iterations;

    public BusinessChatResponse(String sessionId, String turnId, String answer, int iterations) {
        this.sessionId = sessionId;
        this.turnId = turnId;
        this.answer = answer;
        this.iterations = iterations;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTurnId() {
        return turnId;
    }

    public String getAnswer() {
        return answer;
    }

    public int getIterations() {
        return iterations;
    }
}
