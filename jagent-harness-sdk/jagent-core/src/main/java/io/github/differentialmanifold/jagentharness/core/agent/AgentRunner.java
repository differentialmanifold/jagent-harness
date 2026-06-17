package io.github.differentialmanifold.jagentharness.core.agent;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.conversation.ConversationContext;
import io.github.differentialmanifold.jagentharness.core.conversation.ConversationContextManager;
import io.github.differentialmanifold.jagentharness.core.conversation.ConversationContextRequest;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.session.SessionStore;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptContext;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContextFactory;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;

public class AgentRunner implements AgentHarness {

    private final AgentSettings settings;
    private final SessionStore sessionStore;
    private final AgentEventPublisher eventPublisher;
    private final PromptProvider promptProvider;
    private final ToolRegistry toolRegistry;
    private final ModelProviderRegistry providerRegistry;
    private final ToolContextFactory toolContextFactory;
    private final ConversationContextManager conversationContextManager;
    private final ObjectMapper objectMapper;

    public AgentRunner(AgentSettings settings,
                       SessionStore sessionStore,
                       AgentEventPublisher eventPublisher,
                       PromptProvider promptProvider,
                       ToolRegistry toolRegistry,
                       ModelProviderRegistry providerRegistry,
                       ToolContextFactory toolContextFactory,
                       ConversationContextManager conversationContextManager,
                       ObjectMapper objectMapper) {
        this.settings = settings;
        this.sessionStore = sessionStore;
        this.eventPublisher = eventPublisher;
        this.promptProvider = promptProvider;
        this.toolRegistry = toolRegistry;
        this.providerRegistry = providerRegistry;
        this.toolContextFactory = toolContextFactory;
        this.conversationContextManager = conversationContextManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentRunResult run(String sessionId, String userText) {
        return run(sessionId, userText, AgentRunOptions.empty());
    }

    @Override
    public AgentRunResult run(String sessionId, String userText, AgentRunOptions options) {
        AgentRunOptions effectiveOptions = options == null ? AgentRunOptions.empty() : options;
        return eventPublisher.withEventConsumer(
                effectiveOptions.getEventConsumer(),
                () -> runInternal(sessionId, userText, effectiveOptions));
    }

    private AgentRunResult runInternal(String sessionId,
                                       String userText,
                                       AgentRunOptions effectiveOptions) {
        SessionRecord session = sessionStore.requireSession(sessionId);

        String turnId = Ids.newId("turn");
        ToolContext toolContext = createToolContext(session, turnId, effectiveOptions);
        Map<String, Object> turnPayload = turnPayload(sessionId, turnId, toolContext);

        publish(sessionId, turnId, AgentEvent.AGENT_START, turnPayload);
        publish(sessionId, turnId, AgentEvent.TURN_START, turnPayload);

        String parentMessageId = lastMessageId(sessionStore.findMessages(sessionId));
        AgentMessage userMessage = AgentMessage.user(sessionId, userText);
        userMessage.setTurnId(turnId);
        userMessage.setParentMessageId(parentMessageId);
        sessionStore.appendMessage(userMessage);
        parentMessageId = userMessage.getMessageId();
        publish(sessionId, turnId, AgentEvent.MESSAGE_END, eventPayload("message", userMessage));

        String answer = "";
        int iterations = 0;
        StringBuilder partialAnswer = new StringBuilder();
        StopSignal stopSignal = effectiveOptions.getStopSignal();

        try {
            while (true) {
                stopSignal.throwIfAborted();
                iterations++;
                partialAnswer.setLength(0);
                publish(sessionId, turnId, AgentEvent.MESSAGE_START,
                        singleton("iteration", iterations));

                Collection<ToolDefinition> tools = toolRegistry.all();
                ModelProvider provider = requireProvider();
                String systemPrompt = promptProvider.buildSystemPrompt(new PromptContext(tools, toolContext));
                List<AgentMessage> storedMessages = sessionStore.findMessages(sessionId);
                ConversationContext conversationContext = conversationContextManager.prepare(
                        new ConversationContextRequest(
                                sessionId,
                                turnId,
                                systemPrompt,
                                storedMessages,
                                tools,
                                provider,
                                stopSignal));

                ModelRequest request = new ModelRequest();
                request.setModel(settings.getModel());
                request.setTemperature(settings.getTemperature());
                request.setSystemPrompt(conversationContext.getSystemPrompt());
                request.setMessages(conversationContext.getMessages());
                request.setTools(tools);

                final int[] deltaIndex = new int[]{0};
                ModelResponse response = provider.chat(
                        request,
                        delta -> {
                            partialAnswer.append(delta);
                            publishAssistantTextUpdate(
                                    sessionId,
                                    turnId,
                                    delta,
                                    deltaIndex[0]++);
                        },
                        stopSignal);
                stopSignal.throwIfAborted();
                AgentMessage assistantMessage = AgentMessage.assistant(
                        sessionId,
                        response.getContent(),
                        response.getToolCalls());
                assistantMessage.setTurnId(turnId);
                assistantMessage.setParentMessageId(parentMessageId);
                assistantMessage.setMetadataJson(response.getRawJson());
                sessionStore.appendMessage(assistantMessage);
                parentMessageId = assistantMessage.getMessageId();
                partialAnswer.setLength(0);
                publish(sessionId, turnId, AgentEvent.MESSAGE_END,
                        eventPayload("message", assistantMessage));

                answer = response.getContent();
                if (response.getToolCalls() == null || response.getToolCalls().isEmpty()) {
                    break;
                }

                parentMessageId = executeToolCalls(
                        sessionId,
                        turnId,
                        toolContext,
                        response.getToolCalls(),
                        parentMessageId);
            }
        } catch (StopRequestedException e) {
            AgentMessage stoppedMessage = appendStoppedAssistantMessage(
                    sessionId,
                    turnId,
                    partialAnswer.toString());
            publishStopped(sessionId, turnId, iterations, stoppedMessage.getMessageId());
            sessionStore.touch(sessionId);
            throw e;
        } catch (RuntimeException e) {
            if (stopSignal.isAborted()) {
                AgentMessage stoppedMessage = appendStoppedAssistantMessage(
                        sessionId,
                        turnId,
                        partialAnswer.toString());
                publishStopped(sessionId, turnId, iterations, stoppedMessage.getMessageId());
                sessionStore.touch(sessionId);
                throw new StopRequestedException(e);
            }
            throw e;
        }

        AgentRunResult result = new AgentRunResult();
        result.setSessionId(sessionId);
        result.setTurnId(turnId);
        result.setAnswer(answer);
        result.setIterations(iterations);

        publish(sessionId, turnId, AgentEvent.TURN_END, result);
        publish(sessionId, turnId, AgentEvent.AGENT_END, result);
        sessionStore.touch(sessionId);
        return result;
    }

    private String executeToolCalls(String sessionId,
                                    String turnId,
                                    ToolContext toolContext,
                                    List<ToolCall> toolCalls,
                                    String parentMessageId) {
        StopSignal stopSignal = toolContext.getStopSignal();
        for (int index = 0; index < toolCalls.size(); index++) {
            ToolCall call = toolCalls.get(index);
            try {
                stopSignal.throwIfAborted();
            } catch (StopRequestedException e) {
                appendStoppedToolMessages(
                        sessionId,
                        turnId,
                        toolCalls.subList(index, toolCalls.size()),
                        parentMessageId);
                throw e;
            }
            Map<String, Object> startPayload = new LinkedHashMap<String, Object>();
            startPayload.put("toolCallId", call.getToolCallId());
            startPayload.put("toolName", call.getName());
            startPayload.put("arguments", call.getArgumentsJson());
            publish(sessionId, turnId, AgentEvent.TOOL_EXECUTION_START, startPayload);

            ToolExecutionResult result;
            try {
                result = executeToolCall(toolContext, call);
                stopSignal.throwIfAborted();
            } catch (StopRequestedException e) {
                appendStoppedToolMessages(
                        sessionId,
                        turnId,
                        toolCalls.subList(index, toolCalls.size()),
                        parentMessageId);
                throw e;
            }
            AgentMessage toolMessage = AgentMessage.tool(
                    sessionId,
                    call.getToolCallId(),
                    call.getName(),
                    result.getContent());
            toolMessage.setTurnId(turnId);
            toolMessage.setParentMessageId(parentMessageId);
            sessionStore.appendMessage(toolMessage);
            parentMessageId = toolMessage.getMessageId();

            Map<String, Object> endPayload = new LinkedHashMap<String, Object>();
            endPayload.put("toolCallId", call.getToolCallId());
            endPayload.put("toolName", call.getName());
            endPayload.put("result", result.getContent());
            publish(sessionId, turnId, AgentEvent.TOOL_EXECUTION_END, endPayload);
            publish(sessionId, turnId, AgentEvent.MESSAGE_END, eventPayload("message", toolMessage));
        }
        return parentMessageId;
    }

    private ToolExecutionResult executeToolCall(ToolContext toolContext, ToolCall call) {
        ToolContext callContext = toolContext.forToolCall(call.getToolCallId(), call.getName());
        StopSignal stopSignal = callContext.getStopSignal();
        stopSignal.throwIfAborted();
        ToolDefinition tool = toolRegistry.get(call.getName());
        if (tool == null) {
            return ToolExecutionResult.error("Tool not found: " + call.getName());
        }
        try (StopRegistration ignored = stopSignal.onStop(Thread.currentThread()::interrupt)) {
            JsonNode arguments = parseArguments(call.getArgumentsJson());
            ToolExecutionResult result = tool.execute(callContext, arguments);
            stopSignal.throwIfAborted();
            return result;
        } catch (StopRequestedException e) {
            Thread.interrupted();
            throw e;
        } catch (InterruptedException e) {
            if (stopSignal.isAborted()) {
                Thread.interrupted();
                throw new StopRequestedException(e);
            }
            Thread.currentThread().interrupt();
            return ToolExecutionResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolExecutionResult.error(e.getMessage());
        }
    }

    private JsonNode parseArguments(String json) throws IOException {
        if (json == null || json.trim().isEmpty()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(json);
    }

    private ModelProvider requireProvider() {
        ModelProvider provider = providerRegistry.get(settings.getProvider());
        if (provider == null) {
            throw new IllegalStateException("No model provider registered for: " + settings.getProvider());
        }
        return provider;
    }

    private String lastMessageId(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        AgentMessage lastMessage = messages.get(messages.size() - 1);
        return lastMessage == null ? null : lastMessage.getMessageId();
    }

    private ToolContext createToolContext(SessionRecord session, String turnId, AgentRunOptions options) {
        ToolContext context = toolContextFactory.create(session, turnId, options);
        if (context != null) {
            return withConfigRoot(context);
        }
        return withConfigRoot(new ToolContext(
                session.getSessionId(),
                turnId,
                options.getTraceId(),
                null,
                null,
                options.getAttributes(),
                options.getStopSignal(),
                options.getApprovalMode(),
                options.getApprovalHandler(),
                null,
                null));
    }

    private ToolContext withConfigRoot(ToolContext context) {
        if (context == null || context.getConfigRoot() != null || settings.getConfigRoot() == null) {
            return context;
        }
        return new ToolContext(
                context.getSessionId(),
                context.getTurnId(),
                context.getTraceId(),
                context.getWorkspaceRoot(),
                settings.getConfigRoot(),
                context.getAttributes(),
                context.getStopSignal(),
                context.getApprovalMode(),
                context.getApprovalHandler(),
                context.getCurrentToolCallId(),
                context.getCurrentToolName());
    }

    private AgentMessage appendStoppedAssistantMessage(String sessionId,
                                                       String turnId,
                                                       String content) {
        AgentMessage message = AgentMessage.assistant(
                sessionId,
                content == null ? "" : content,
                Collections.<ToolCall>emptyList());
        message.setTurnId(turnId);
        message.setParentMessageId(lastMessageId(sessionStore.findMessages(sessionId)));
        message.setStopReason(AgentMessage.STOP_REASON_ABORTED);
        sessionStore.appendMessage(message);
        publish(sessionId, turnId, AgentEvent.MESSAGE_END, eventPayload("message", message));
        return message;
    }

    private String appendStoppedToolMessages(String sessionId,
                                             String turnId,
                                             List<ToolCall> toolCalls,
                                             String parentMessageId) {
        String currentParentMessageId = parentMessageId;
        for (ToolCall call : toolCalls) {
            ToolExecutionResult result = ToolExecutionResult.error("Tool execution stopped");
            AgentMessage toolMessage = AgentMessage.tool(
                    sessionId,
                    call.getToolCallId(),
                    call.getName(),
                    result.getContent());
            toolMessage.setTurnId(turnId);
            toolMessage.setParentMessageId(currentParentMessageId);
            sessionStore.appendMessage(toolMessage);
            currentParentMessageId = toolMessage.getMessageId();

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("toolCallId", call.getToolCallId());
            payload.put("toolName", call.getName());
            payload.put("result", result.getContent());
            payload.put("stopped", true);
            publish(sessionId, turnId, AgentEvent.TOOL_EXECUTION_END, payload);
            publish(sessionId, turnId, AgentEvent.MESSAGE_END, eventPayload("message", toolMessage));
        }
        return currentParentMessageId;
    }

    private void publishStopped(String sessionId,
                                String turnId,
                                int iterations,
                                String messageId) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", "stopped");
        payload.put("stopReason", AgentMessage.STOP_REASON_ABORTED);
        payload.put("messageId", messageId);
        payload.put("iterations", iterations);
        publish(sessionId, turnId, AgentEvent.TURN_END, payload);
        publish(sessionId, turnId, AgentEvent.AGENT_STOPPED, payload);
    }

    private Map<String, Object> turnPayload(String sessionId, String turnId, ToolContext context) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", sessionId);
        payload.put("turnId", turnId);
        if (context != null) {
            putIfNotEmpty(payload, "traceId", context.getTraceId());
        }
        return payload;
    }

    private void putIfNotEmpty(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            payload.put(key, value);
        }
    }

    private void publishAssistantTextUpdate(String sessionId,
                                            String turnId,
                                            String delta,
                                            int index) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("role", AgentMessage.ROLE_ASSISTANT);
        payload.put("delta", delta);
        payload.put("index", index);
        publish(sessionId, turnId, AgentEvent.MESSAGE_UPDATE, payload);
    }

    private AgentEvent publish(String sessionId,
                               String turnId,
                               String type,
                               Object payload) {
        return eventPublisher.publish(sessionId, turnId, type, payload);
    }

    private Map<String, Object> singleton(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }

    private Map<String, Object> eventPayload(String key, Object value) {
        if (value == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }

}
