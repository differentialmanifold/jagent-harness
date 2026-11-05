package io.github.differentialmanifold.jagentharness.core.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Durable failure receipts preserve display history without replaying rejected inputs. */
public final class FailedTurn {
    private static final ObjectMapper JSON = new ObjectMapper();

    private FailedTurn() {}

    public static AgentMessage receipt(
            List<AgentMessage> history,
            String sessionId,
            String runId,
            String turnId,
            String error) {
        List<String> excluded = new ArrayList<>();
        boolean accepted =
                history.stream()
                        .anyMatch(
                                m ->
                                        Objects.equals(runId, m.getRunId())
                                                && Objects.equals(turnId, m.getTurnId())
                                                && "assistant".equals(m.getRole())
                                                && !"failed".equals(m.getStopReason()));
        if (!accepted) {
            for (AgentMessage message : history) {
                if ("user".equals(message.getRole())
                        && Objects.equals(runId, message.getRunId())
                        && Objects.equals(turnId, message.getTurnId()))
                    excluded.add(message.getMessageId());
            }
        }
        ObjectNode metadata = JSON.createObjectNode().put("kind", "model_failure");
        metadata.set("excludedMessageIds", JSON.valueToTree(excluded));
        AgentMessage receipt = AgentMessage.assistant(sessionId, error, Collections.emptyList());
        receipt.setRunId(runId);
        receipt.setTurnId(turnId);
        receipt.setStopReason("failed");
        receipt.setMetadataJson(metadata.toString());
        return receipt;
    }

    public static List<AgentMessage> context(List<AgentMessage> history) {
        Set<String> excluded = new HashSet<>();
        for (AgentMessage message : history) {
            if (!"failed".equals(message.getStopReason()) || message.getMetadataJson() == null)
                continue;
            try {
                JsonNode metadata = JSON.readTree(message.getMetadataJson());
                if (metadata != null)
                    for (JsonNode id : metadata.path("excludedMessageIds"))
                        excluded.add(id.asText());
            } catch (java.io.IOException | IllegalArgumentException e) {
                throw new IllegalStateException("Invalid failure receipt metadata", e);
            }
        }
        List<AgentMessage> result = new ArrayList<>();
        for (AgentMessage message : history) {
            if (!"failed".equals(message.getStopReason())
                    && !excluded.contains(message.getMessageId())) result.add(message);
        }
        return result;
    }
}
