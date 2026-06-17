package io.github.differentialmanifold.jagentharness.core.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalHandler;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalMode;

public class AgentRunOptions {

    private final String traceId;
    private final Map<String, Object> attributes;
    private final Consumer<AgentEvent> eventConsumer;
    private final StopSignal stopSignal;
    private final ToolApprovalMode approvalMode;
    private final ToolApprovalHandler approvalHandler;

    private AgentRunOptions(Builder builder) {
        this.traceId = builder.traceId;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.attributes));
        this.eventConsumer = builder.eventConsumer;
        this.stopSignal = builder.stopSignal == null ? StopSignal.none() : builder.stopSignal;
        this.approvalMode = builder.approvalMode == null ? ToolApprovalMode.FULL_ACCESS : builder.approvalMode;
        this.approvalHandler = builder.approvalHandler;
    }

    public static AgentRunOptions empty() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTraceId() {
        return traceId;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public Consumer<AgentEvent> getEventConsumer() {
        return eventConsumer;
    }

    public StopSignal getStopSignal() {
        return stopSignal;
    }

    public ToolApprovalMode getApprovalMode() {
        return approvalMode;
    }

    public ToolApprovalHandler getApprovalHandler() {
        return approvalHandler;
    }

    public AgentRunOptions withEventConsumer(Consumer<AgentEvent> eventConsumer) {
        return toBuilder().eventConsumer(eventConsumer).build();
    }

    public Builder toBuilder() {
        return builder()
                .traceId(traceId)
                .attributes(attributes)
                .eventConsumer(eventConsumer)
                .stopSignal(stopSignal)
                .approvalMode(approvalMode)
                .approvalHandler(approvalHandler);
    }

    public static class Builder {
        private String traceId;
        private final Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        private Consumer<AgentEvent> eventConsumer;
        private StopSignal stopSignal = StopSignal.none();
        private ToolApprovalMode approvalMode = ToolApprovalMode.FULL_ACCESS;
        private ToolApprovalHandler approvalHandler;

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder attribute(String key, Object value) {
            if (key != null && value != null) {
                attributes.put(key, value);
            }
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            if (attributes != null) {
                for (Map.Entry<String, Object> entry : attributes.entrySet()) {
                    attribute(entry.getKey(), entry.getValue());
                }
            }
            return this;
        }

        public Builder eventConsumer(Consumer<AgentEvent> eventConsumer) {
            this.eventConsumer = eventConsumer;
            return this;
        }

        public Builder stopSignal(StopSignal stopSignal) {
            this.stopSignal = stopSignal == null ? StopSignal.none() : stopSignal;
            return this;
        }

        public Builder approvalMode(ToolApprovalMode approvalMode) {
            this.approvalMode = approvalMode == null ? ToolApprovalMode.FULL_ACCESS : approvalMode;
            return this;
        }

        public Builder approvalHandler(ToolApprovalHandler approvalHandler) {
            this.approvalHandler = approvalHandler;
            return this;
        }

        public AgentRunOptions build() {
            return new AgentRunOptions(this);
        }
    }
}
