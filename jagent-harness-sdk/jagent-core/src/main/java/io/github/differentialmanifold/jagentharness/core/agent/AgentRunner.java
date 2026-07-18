package io.github.differentialmanifold.jagentharness.core.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.conversation.ConversationContext;
import io.github.differentialmanifold.jagentharness.core.conversation.ConversationContextManager;
import io.github.differentialmanifold.jagentharness.core.conversation.ConversationContextRequest;
import io.github.differentialmanifold.jagentharness.core.conversation.TokenEstimator;
import io.github.differentialmanifold.jagentharness.core.support.Ids;
import io.github.differentialmanifold.jagentharness.core.session.SessionRecord;
import io.github.differentialmanifold.jagentharness.core.session.SessionStore;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptContext;
import io.github.differentialmanifold.jagentharness.core.prompt.PromptProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderRegistry;
import io.github.differentialmanifold.jagentharness.core.provider.ModelDeltaConsumer;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContextFactory;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolExecutionResult;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsage;
import io.github.differentialmanifold.jagentharness.core.usage.ModelCallUsageStore;
import io.github.differentialmanifold.jagentharness.core.usage.NoopModelCallUsageStore;

public class AgentRunner implements AgentHarness {

    private static final Logger LOGGER = Logger.getLogger(AgentRunner.class.getName());

    private final AgentSettings settings;
    private final SessionStore sessionStore;
    private final AgentEventPublisher eventPublisher;
    private final PromptProvider promptProvider;
    private final ToolRegistry toolRegistry;
    private final ModelProviderRegistry providerRegistry;
    private final ToolContextFactory toolContextFactory;
    private final ConversationContextManager conversationContextManager;
    private final ModelCallUsageStore modelCallUsageStore;
    private final ObjectMapper objectMapper;
    private final ModelCallRetryExecutor modelCallRetryExecutor;
    private final TokenEstimator tokenEstimator = new TokenEstimator();

    public AgentRunner(AgentSettings settings,
                       SessionStore sessionStore,
                       AgentEventPublisher eventPublisher,
                       PromptProvider promptProvider,
                       ToolRegistry toolRegistry,
                       ModelProviderRegistry providerRegistry,
                       ToolContextFactory toolContextFactory,
                       ConversationContextManager conversationContextManager,
                       ObjectMapper objectMapper) {
        this(
                settings,
                sessionStore,
                eventPublisher,
                promptProvider,
                toolRegistry,
                providerRegistry,
                toolContextFactory,
                conversationContextManager,
                new NoopModelCallUsageStore(),
                objectMapper);
    }

    public AgentRunner(AgentSettings settings,
                       SessionStore sessionStore,
                       AgentEventPublisher eventPublisher,
                       PromptProvider promptProvider,
                       ToolRegistry toolRegistry,
                       ModelProviderRegistry providerRegistry,
                       ToolContextFactory toolContextFactory,
                       ConversationContextManager conversationContextManager,
                       ModelCallUsageStore modelCallUsageStore,
                       ObjectMapper objectMapper) {
        this.settings = settings;
        this.sessionStore = sessionStore;
        this.eventPublisher = eventPublisher;
        this.promptProvider = promptProvider;
        this.toolRegistry = toolRegistry;
        this.providerRegistry = providerRegistry;
        this.toolContextFactory = toolContextFactory;
        this.conversationContextManager = conversationContextManager;
        this.modelCallUsageStore = modelCallUsageStore == null ? new NoopModelCallUsageStore() : modelCallUsageStore;
        this.objectMapper = objectMapper;
        this.modelCallRetryExecutor = new ModelCallRetryExecutor(settings, eventPublisher);
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
        String runId = valueOrNewRunId(effectiveOptions.getRunId());
        String parentMessageId = lastMessageId(sessionStore.findMessages(sessionId));
        List<String> pendingUserContents = userText == null
                ? Collections.<String>emptyList()
                : Collections.singletonList(userText);
        String answer = "";
        int turnCount = 0;
        String firstTurnId = null;
        String currentTurnId = null;
        boolean currentTurnEnded = true;
        StringBuilder partialAnswer = new StringBuilder();
        StringBuilder partialReasoning = new StringBuilder();
        StopSignal stopSignal = effectiveOptions.getStopSignal();

        publish(sessionId, runId, null, AgentEvent.AGENT_START,
                runPayload(sessionId, runId, effectiveOptions));

        try {
            while (true) {
                turnCount++;
                currentTurnId = Ids.newId("turn");
                final String turnId = currentTurnId;
                if (firstTurnId == null) {
                    firstTurnId = turnId;
                }
                currentTurnEnded = false;
                partialAnswer.setLength(0);
                partialReasoning.setLength(0);

                ToolContext toolContext = createToolContext(
                        session,
                        runId,
                        turnId,
                        effectiveOptions);
                publish(sessionId, runId, turnId, AgentEvent.TURN_START,
                        turnPayload(sessionId, runId, turnId, turnCount, toolContext));

                if (!pendingUserContents.isEmpty()) {
                    for (String pendingUserContent : pendingUserContents) {
                        AgentMessage userMessage = AgentMessage.user(sessionId, pendingUserContent);
                        userMessage.setRunId(runId);
                        userMessage.setTurnId(turnId);
                        userMessage.setParentMessageId(parentMessageId);
                        sessionStore.appendMessage(userMessage);
                        parentMessageId = userMessage.getMessageId();
                        publish(sessionId, runId, turnId, AgentEvent.MESSAGE_END,
                                eventPayload("message", userMessage));
                    }
                    pendingUserContents = Collections.emptyList();
                }

                stopSignal.throwIfAborted();
                publish(sessionId, runId, turnId, AgentEvent.MESSAGE_START,
                        singleton("turnIndex", turnCount));

                Collection<ToolDefinition> tools = toolRegistry.all(toolContext);
                ModelProvider provider = requireProvider();
                String systemPrompt = promptProvider.buildSystemPrompt(new PromptContext(tools, toolContext));
                List<AgentMessage> storedMessages = sessionStore.findMessages(sessionId);
                ConversationContext conversationContext = conversationContextManager.prepare(
                        new ConversationContextRequest(
                                sessionId,
                                runId,
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

                final int[] contentDeltaIndex = new int[]{0};
                final int[] reasoningDeltaIndex = new int[]{0};
                ModelResponse response = modelCallRetryExecutor.call(
                        provider,
                        request,
                        new ModelDeltaConsumer() {
                            @Override
                            public void onContentDelta(String delta) {
                                partialAnswer.append(delta);
                                publishAssistantTextUpdate(
                                        sessionId,
                                        runId,
                                        turnId,
                                        delta,
                                        contentDeltaIndex[0]++);
                            }

                            @Override
                            public void onReasoningDelta(String delta) {
                                partialReasoning.append(delta);
                                publishAssistantReasoningUpdate(
                                        sessionId,
                                        runId,
                                        turnId,
                                        delta,
                                        reasoningDeltaIndex[0]++);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                partialAnswer.setLength(0);
                                partialReasoning.setLength(0);
                                contentDeltaIndex[0] = 0;
                                reasoningDeltaIndex[0] = 0;
                            }
                        },
                        stopSignal,
                        sessionId,
                        runId,
                        turnId);
                stopSignal.throwIfAborted();
                AgentMessage assistantMessage = AgentMessage.assistant(
                        sessionId,
                        response.getContent(),
                        response.getToolCalls());
                assistantMessage.setReasoningContent(response.getReasoningContent());
                assistantMessage.setRunId(runId);
                assistantMessage.setTurnId(turnId);
                assistantMessage.setParentMessageId(parentMessageId);
                sessionStore.appendMessage(assistantMessage);
                parentMessageId = assistantMessage.getMessageId();
                partialAnswer.setLength(0);
                partialReasoning.setLength(0);
                publish(sessionId, runId, turnId, AgentEvent.MESSAGE_END,
                        eventPayload("message", assistantMessage));
                publishContextUsage(
                        sessionId,
                        runId,
                        turnId,
                        provider,
                        conversationContext,
                        assistantMessage,
                        response);

                answer = response.getContent();
                boolean hasToolCalls = response.getToolCalls() != null
                        && !response.getToolCalls().isEmpty();
                if (hasToolCalls) {
                    parentMessageId = executeToolCalls(
                            sessionId,
                            runId,
                            turnId,
                            toolContext,
                            response.getToolCalls(),
                            parentMessageId);
                }

                publish(sessionId, runId, turnId, AgentEvent.TURN_END,
                        completedTurnPayload(turnCount, hasToolCalls));
                currentTurnEnded = true;

                List<RunInput> pendingInputs = claimPendingInputs(
                        effectiveOptions.getRunInputSource(),
                        sessionId,
                        runId,
                        turnId);
                if (!pendingInputs.isEmpty()) {
                    pendingUserContents = runInputContents(pendingInputs);
                    publishRunInputsApplied(sessionId, runId, turnId, pendingInputs);
                    continue;
                }
                if (hasToolCalls) {
                    continue;
                }
                break;
            }
        } catch (StopRequestedException e) {
            AgentMessage stoppedMessage = appendStoppedAssistantMessage(
                    sessionId,
                    runId,
                    currentTurnId,
                    partialAnswer.toString(),
                    partialReasoning.toString());
            publishStopped(
                    sessionId,
                    runId,
                    currentTurnId,
                    turnCount,
                    stoppedMessage.getMessageId(),
                    currentTurnEnded);
            sessionStore.touch(sessionId);
            throw e;
        } catch (RuntimeException e) {
            if (stopSignal.isAborted()) {
                AgentMessage stoppedMessage = appendStoppedAssistantMessage(
                        sessionId,
                        runId,
                        currentTurnId,
                        partialAnswer.toString(),
                        partialReasoning.toString());
                publishStopped(
                        sessionId,
                        runId,
                        currentTurnId,
                        turnCount,
                        stoppedMessage.getMessageId(),
                        currentTurnEnded);
                sessionStore.touch(sessionId);
                throw new StopRequestedException(e);
            }
            publishFailed(sessionId, runId, currentTurnId, turnCount, e, currentTurnEnded);
            sessionStore.touch(sessionId);
            throw e;
        }

        AgentRunResult result = new AgentRunResult();
        result.setSessionId(sessionId);
        result.setRunId(runId);
        result.setFirstTurnId(firstTurnId);
        result.setLastTurnId(currentTurnId);
        result.setAnswer(answer);
        result.setTurnCount(turnCount);

        publish(sessionId, runId, null, AgentEvent.AGENT_END, result);
        sessionStore.touch(sessionId);
        return result;
    }

    private String executeToolCalls(String sessionId,
                                    String runId,
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
                        runId,
                        turnId,
                        toolCalls.subList(index, toolCalls.size()),
                        parentMessageId);
                throw e;
            }
            Map<String, Object> startPayload = new LinkedHashMap<String, Object>();
            startPayload.put("toolCallId", call.getToolCallId());
            startPayload.put("toolName", call.getName());
            startPayload.put("arguments", call.getArgumentsJson());
            publish(sessionId, runId, turnId, AgentEvent.TOOL_EXECUTION_START, startPayload);

            ToolExecutionResult result;
            try {
                result = executeToolCall(toolContext, call);
                stopSignal.throwIfAborted();
            } catch (StopRequestedException e) {
                appendStoppedToolMessages(
                        sessionId,
                        runId,
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
            toolMessage.setRunId(runId);
            toolMessage.setTurnId(turnId);
            toolMessage.setParentMessageId(parentMessageId);
            sessionStore.appendMessage(toolMessage);
            parentMessageId = toolMessage.getMessageId();

            Map<String, Object> endPayload = new LinkedHashMap<String, Object>();
            endPayload.put("toolCallId", call.getToolCallId());
            endPayload.put("toolName", call.getName());
            endPayload.put("result", result.getContent());
            publish(sessionId, runId, turnId, AgentEvent.TOOL_EXECUTION_END, endPayload);
            publish(sessionId, runId, turnId, AgentEvent.MESSAGE_END, eventPayload("message", toolMessage));
        }
        return parentMessageId;
    }

    private ToolExecutionResult executeToolCall(ToolContext toolContext, ToolCall call) {
        ToolContext callContext = toolContext.forToolCall(call.getToolCallId(), call.getName());
        StopSignal stopSignal = callContext.getStopSignal();
        stopSignal.throwIfAborted();
        ToolDefinition tool = toolRegistry.get(call.getName(), toolContext);
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

    private String valueOrNewRunId(String runId) {
        return runId == null || runId.trim().isEmpty() ? Ids.newId("run") : runId.trim();
    }

    private List<RunInput> claimPendingInputs(RunInputSource source,
                                              String sessionId,
                                              String runId,
                                              String completedTurnId) {
        RunInputSource effectiveSource = source == null ? RunInputSource.none() : source;
        List<RunInput> claimed = effectiveSource.claimPendingInputs(
                sessionId, runId, completedTurnId);
        if (claimed == null || claimed.isEmpty()) {
            return Collections.emptyList();
        }
        for (RunInput input : claimed) {
            if (input == null || input.getContent() == null || input.getContent().trim().isEmpty()) {
                throw new IllegalStateException("A claimed run input must contain non-blank content");
            }
        }
        return new ArrayList<RunInput>(claimed);
    }

    private List<String> runInputContents(List<RunInput> inputs) {
        List<String> contents = new ArrayList<String>(inputs.size());
        for (RunInput input : inputs) {
            contents.add(input.getContent());
        }
        return contents;
    }

    private void publishRunInputsApplied(String sessionId,
                                         String runId,
                                         String completedTurnId,
                                         List<RunInput> inputs) {
        List<String> inputIds = new ArrayList<String>();
        List<Map<String, Object>> inputPayloads = new ArrayList<Map<String, Object>>();
        for (RunInput input : inputs) {
            inputIds.add(input.getInputId());
            Map<String, Object> inputPayload = new LinkedHashMap<String, Object>();
            inputPayload.put("inputId", input.getInputId());
            inputPayloads.add(inputPayload);
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("claimedAfterTurnId", completedTurnId);
        payload.put("inputs", inputPayloads);
        payload.put("inputIds", inputIds);
        payload.put("inputCount", inputIds.size());
        publish(sessionId, runId, completedTurnId, AgentEvent.RUN_INPUT_BATCH_APPLIED, payload);
    }

    private Map<String, Object> completedTurnPayload(int turnIndex, boolean hasToolCalls) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", "completed");
        payload.put("turnIndex", turnIndex);
        payload.put("hasToolCalls", hasToolCalls);
        return payload;
    }

    private void publishFailed(String sessionId,
                               String runId,
                               String turnId,
                               int turnCount,
                               RuntimeException error,
                               boolean turnEnded) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", "failed");
        payload.put("turnCount", turnCount);
        payload.put("error", error == null ? "" : error.getMessage());
        if (!turnEnded && turnId != null) {
            publish(sessionId, runId, turnId, AgentEvent.TURN_END, payload);
        }
        publish(sessionId, runId, null, AgentEvent.AGENT_ERROR, payload);
    }

    private ToolContext createToolContext(SessionRecord session,
                                          String runId,
                                          String turnId,
                                          AgentRunOptions options) {
        ToolContext context = toolContextFactory.create(session, runId, turnId, options);
        if (context != null) {
            return withConfigRoot(context);
        }
        return withConfigRoot(new ToolContext(
                session.getSessionId(),
                runId,
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
                context.getRunId(),
                context.getTurnId(),
                context.getTraceId(),
                context.getWorkspaceRoot(),
                settings.getConfigRoot(),
                context.getAttributes(),
                context.getStopSignal(),
                context.getApprovalMode(),
                context.getApprovalHandler(),
                context.getCurrentToolCallId(),
                context.getCurrentToolName(),
                context.getProjectId());
    }

    private AgentMessage appendStoppedAssistantMessage(String sessionId,
                                                       String runId,
                                                       String turnId,
                                                       String content,
                                                       String reasoningContent) {
        AgentMessage message = AgentMessage.assistant(
                sessionId,
                content == null ? "" : content,
                Collections.<ToolCall>emptyList());
        message.setReasoningContent(reasoningContent == null ? "" : reasoningContent);
        message.setRunId(runId);
        message.setTurnId(turnId);
        message.setParentMessageId(lastMessageId(sessionStore.findMessages(sessionId)));
        message.setStopReason(AgentMessage.STOP_REASON_ABORTED);
        sessionStore.appendMessage(message);
        publish(sessionId, runId, turnId, AgentEvent.MESSAGE_END, eventPayload("message", message));
        return message;
    }

    private String appendStoppedToolMessages(String sessionId,
                                             String runId,
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
            toolMessage.setRunId(runId);
            toolMessage.setTurnId(turnId);
            toolMessage.setParentMessageId(currentParentMessageId);
            sessionStore.appendMessage(toolMessage);
            currentParentMessageId = toolMessage.getMessageId();

            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("toolCallId", call.getToolCallId());
            payload.put("toolName", call.getName());
            payload.put("result", result.getContent());
            payload.put("stopped", true);
            publish(sessionId, runId, turnId, AgentEvent.TOOL_EXECUTION_END, payload);
            publish(sessionId, runId, turnId, AgentEvent.MESSAGE_END, eventPayload("message", toolMessage));
        }
        return currentParentMessageId;
    }

    private void publishStopped(String sessionId,
                                String runId,
                                String turnId,
                                int turnCount,
                                String messageId,
                                boolean turnEnded) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", "stopped");
        payload.put("stopReason", AgentMessage.STOP_REASON_ABORTED);
        payload.put("messageId", messageId);
        payload.put("turnCount", turnCount);
        if (!turnEnded && turnId != null) {
            publish(sessionId, runId, turnId, AgentEvent.TURN_END, payload);
        }
        publish(sessionId, runId, null, AgentEvent.AGENT_STOPPED, payload);
    }

    private Map<String, Object> runPayload(String sessionId,
                                           String runId,
                                           AgentRunOptions options) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", sessionId);
        payload.put("runId", runId);
        if (options != null) {
            putIfNotEmpty(payload, "traceId", options.getTraceId());
        }
        return payload;
    }

    private Map<String, Object> turnPayload(String sessionId,
                                            String runId,
                                            String turnId,
                                            int turnIndex,
                                            ToolContext context) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", sessionId);
        payload.put("runId", runId);
        payload.put("turnId", turnId);
        payload.put("turnIndex", turnIndex);
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
                                            String runId,
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
        publish(sessionId, runId, turnId, AgentEvent.MESSAGE_UPDATE, payload);
    }

    private void publishAssistantReasoningUpdate(String sessionId,
                                                 String runId,
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
        publish(sessionId, runId, turnId, AgentEvent.MESSAGE_REASONING_UPDATE, payload);
    }

    private void publishContextUsage(String sessionId,
                                     String runId,
                                     String turnId,
                                     ModelProvider provider,
                                     ConversationContext conversationContext,
                                     AgentMessage assistantMessage,
                                     ModelResponse response) {
        int contextWindowTokens = conversationContext.getContextWindowTokens() > 0
                ? conversationContext.getContextWindowTokens()
                : effectiveContextWindowTokens();
        int thresholdTokens = conversationContext.getThresholdTokens() > 0
                ? conversationContext.getThresholdTokens()
                : compactionThresholdTokens(contextWindowTokens);
        String estimateSource = ModelCallUsage.ESTIMATE_SOURCE_FULL;
        Integer estimatedTokens = estimateNextContextTokens(
                conversationContext.getRawEstimatedTokens(),
                assistantMessage);

        ModelCallUsage record = null;
        if (response != null && response.getUsage() != null && response.getUsage().hasTokenCounts()) {
            record = ModelCallUsage.fromUsage(
                    sessionId,
                    runId,
                    turnId,
                    assistantMessage.getMessageId(),
                    provider == null ? "" : provider.getName(),
                    settings.getModel(),
                    contextWindowTokens,
                    thresholdTokens,
                    estimateSource,
                    estimatedTokens,
                    response.getUsage());
            try {
                modelCallUsageStore.append(record);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING,
                        "Failed to persist model usage for session " + sessionId
                                + " and message " + assistantMessage.getMessageId(),
                        e);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("usageId", record == null ? null : record.getUsageId());
        payload.put("messageId", assistantMessage.getMessageId());
        payload.put("provider", provider == null ? "" : provider.getName());
        payload.put("model", settings.getModel());
        payload.put("contextWindowTokens", contextWindowTokens);
        payload.put("thresholdTokens", thresholdTokens);
        payload.put("estimateSource", estimateSource);
        payload.put("estimatedTokens", estimatedTokens);
        if (record != null) {
            payload.put("actualContextTokens", record.getActualContextTokens());
            payload.put("promptTokens", record.getPromptTokens());
            payload.put("completionTokens", record.getCompletionTokens());
            payload.put("reasoningTokens", record.getReasoningTokens());
            payload.put("cachedTokens", record.getCachedTokens());
            payload.put("totalTokens", record.getTotalTokens());
            payload.put("createdAt", record.getCreatedAt());
        }
        publish(sessionId, runId, turnId, AgentEvent.CONTEXT_USAGE, payload);
    }

    private Integer estimateNextContextTokens(int requestEstimatedTokens, AgentMessage assistantMessage) {
        if (requestEstimatedTokens <= 0) {
            return null;
        }
        long estimatedTokens = (long) requestEstimatedTokens
                + tokenEstimator.estimateMessages(Collections.singletonList(assistantMessage));
        return estimatedTokens >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) estimatedTokens;
    }

    private int effectiveContextWindowTokens() {
        return settings.getContextWindowTokens() <= 0 ? 128000 : settings.getContextWindowTokens();
    }

    private int compactionThresholdTokens(int contextWindowTokens) {
        double ratio = settings.getCompactionThresholdRatio() <= 0
                ? 0.8d
                : settings.getCompactionThresholdRatio();
        if (ratio > 1.0d) {
            ratio = 1.0d;
        }
        return Math.max(1, (int) Math.floor(contextWindowTokens * ratio));
    }

    private AgentEvent publish(String sessionId,
                               String runId,
                               String turnId,
                               String type,
                               Object payload) {
        return eventPublisher.publish(sessionId, runId, turnId, type, payload);
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
