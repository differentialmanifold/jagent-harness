package io.github.differentialmanifold.jagentharness.example.business.api;

import io.github.differentialmanifold.jagentharness.core.agent.AgentHarness;
import io.github.differentialmanifold.jagentharness.core.agent.AgentRunResult;
import io.github.differentialmanifold.jagentharness.core.session.SessionManager;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.support.PathsSupport;
import io.github.differentialmanifold.jagentharness.example.business.BusinessSystemDemoProperties;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/business-assistant")
public class BusinessAssistantController {

    private final AgentHarness agentHarness;
    private final SessionManager sessionManager;
    private final BusinessSystemDemoProperties properties;

    public BusinessAssistantController(AgentHarness agentHarness,
                                       SessionManager sessionManager,
                                       BusinessSystemDemoProperties properties) {
        this.agentHarness = agentHarness;
        this.sessionManager = sessionManager;
        this.properties = properties;
    }

    @PostMapping("/chat")
    public BusinessChatResponse chat(@RequestBody BusinessChatRequest request) {
        BusinessChatRequest effectiveRequest = requireRequest(request);
        SessionRecord session = session(effectiveRequest.getSessionId());
        AgentRunResult result = agentHarness.run(session.getSessionId(), effectiveRequest.getMessage());
        return new BusinessChatResponse(
                result.getSessionId(),
                result.getTurnId(),
                result.getAnswer(),
                result.getIterations());
    }

    private BusinessChatRequest requireRequest(BusinessChatRequest request) {
        if (request == null || isBlank(request.getMessage())) {
            throw new IllegalArgumentException("message is required");
        }
        request.setMessage(request.getMessage().trim());
        return request;
    }

    private SessionRecord session(String sessionId) {
        if (!isBlank(sessionId)) {
            return sessionManager.requireSession(sessionId.trim());
        }
        return sessionManager.createSession(
                "Business System Demo",
                PathsSupport.expandUserHome(properties.getWorkspacePath()).toString());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
