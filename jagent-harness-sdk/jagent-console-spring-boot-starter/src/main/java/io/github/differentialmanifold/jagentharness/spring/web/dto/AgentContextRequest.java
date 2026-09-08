package io.github.differentialmanifold.jagentharness.spring.web.dto;

public class AgentContextRequest {

    private String sessionId;
    private java.util.List<io.github.differentialmanifold.jagentharness.protocol.ToolDescriptor>
            clientTools = new java.util.ArrayList<>();
    private String clientInstructions = "";

    public java.util.List<io.github.differentialmanifold.jagentharness.protocol.ToolDescriptor>
            getClientTools() {
        return clientTools;
    }

    public void setClientTools(
            java.util.List<io.github.differentialmanifold.jagentharness.protocol.ToolDescriptor>
                    value) {
        clientTools = value;
    }

    public String getClientInstructions() {
        return clientInstructions;
    }

    public void setClientInstructions(String value) {
        clientInstructions = value;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
}
