package io.github.differentialmanifold.jagentharness.core.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.agent.AgentSettings;
import io.github.differentialmanifold.jagentharness.core.event.DefaultAgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

class DefaultConversationContextManagerTest {

    @Test
    void convertsAbortedAssistantMessageIntoModelContext() {
        DefaultConversationContextManager manager = manager();
        AgentMessage originalUser = AgentMessage.user("s1", "Start a long answer");
        AgentMessage interrupted = AgentMessage.assistant(
                "s1",
                "Partial answer",
                Collections.emptyList());
        interrupted.setStopReason(AgentMessage.STOP_REASON_ABORTED);
        AgentMessage followUp = AgentMessage.user("s1", "Now answer something else");

        ConversationContext context = manager.prepare(request(
                Arrays.asList(originalUser, interrupted, followUp)));

        List<AgentMessage> messages = context.getMessages();
        assertEquals(4, messages.size());
        assertEquals(originalUser, messages.get(0));
        assertEquals(interrupted, messages.get(1));
        assertEquals(AgentMessage.ROLE_USER, messages.get(2).getRole());
        assertTrue(messages.get(2).getContent().contains("interrupted"));
        assertTrue(messages.get(2).getContent().contains("incomplete"));
        assertEquals(followUp, messages.get(3));
    }

    @Test
    void omitsEmptyAbortedAssistantContentButKeepsInterruptionContext() {
        DefaultConversationContextManager manager = manager();
        AgentMessage originalUser = AgentMessage.user("s1", "Start a long answer");
        AgentMessage interrupted = AgentMessage.assistant("s1", "", Collections.emptyList());
        interrupted.setStopReason(AgentMessage.STOP_REASON_ABORTED);
        AgentMessage followUp = AgentMessage.user("s1", "Continue with another task");

        ConversationContext context = manager.prepare(request(
                Arrays.asList(originalUser, interrupted, followUp)));

        List<AgentMessage> messages = context.getMessages();
        assertEquals(3, messages.size());
        assertFalse(messages.contains(interrupted));
        assertEquals(AgentMessage.ROLE_USER, messages.get(1).getRole());
        assertTrue(messages.get(1).getContent().contains("interrupted"));
        assertEquals(followUp, messages.get(2));
    }

    @Test
    void compactionUsesPersistedCursorAndPreservesAbortReason() {
        AgentSettings settings = new AgentSettings();
        settings.setCompactionEnabled(true);
        settings.setContextWindowTokens(1);
        settings.setCompactionThresholdRatio(1.0d);
        settings.setCompactionRecentMessages(1);
        FakeCompactionStore store = new FakeCompactionStore();
        CapturingModelProvider provider = new CapturingModelProvider();
        ObjectMapper objectMapper = new ObjectMapper();
        DefaultConversationContextManager manager = new DefaultConversationContextManager(
                settings,
                store,
                new DefaultAgentEventPublisher(objectMapper),
                objectMapper);

        AgentMessage originalUser = AgentMessage.user("s1", "Start a long answer");
        AgentMessage interrupted = AgentMessage.assistant("s1", "Partial answer", Collections.emptyList());
        interrupted.setStopReason(AgentMessage.STOP_REASON_ABORTED);
        AgentMessage followUp = AgentMessage.user("s1", "Start another task");

        ConversationContext context = manager.prepare(new ConversationContextRequest(
                "s1",
                "t1",
                "System prompt",
                Arrays.asList(originalUser, interrupted, followUp),
                Collections.<ToolDefinition>emptyList(),
                provider));

        assertEquals(interrupted.getMessageId(), store.cursorMessageId);
        assertTrue(provider.compactionPrompt.contains("stopReason=aborted"));
        assertTrue(provider.compactionPrompt.contains("interrupted this assistant response"));
        assertEquals(1, context.getMessages().size());
        assertEquals(followUp, context.getMessages().get(0));
    }

    private DefaultConversationContextManager manager() {
        AgentSettings settings = new AgentSettings();
        settings.setCompactionEnabled(false);
        ObjectMapper objectMapper = new ObjectMapper();
        return new DefaultConversationContextManager(
                settings,
                new NoopCompactionStore(),
                new DefaultAgentEventPublisher(objectMapper),
                objectMapper);
    }

    private ConversationContextRequest request(List<AgentMessage> messages) {
        return new ConversationContextRequest(
                "s1",
                "t1",
                "System prompt",
                messages,
                Collections.<ToolDefinition>emptyList(),
                null);
    }

    private static class FakeCompactionStore implements CompactionStore {
        private String cursorMessageId;

        @Override
        public CompactionState findBySessionId(String sessionId) {
            return null;
        }

        @Override
        public void save(String sessionId, String summary, String cursorMessageId) {
            this.cursorMessageId = cursorMessageId;
        }
    }

    private static class CapturingModelProvider implements ModelProvider {
        private String compactionPrompt;

        @Override
        public String getName() {
            return "capturing";
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            compactionPrompt = request.getMessages().get(0).getContent();
            ModelResponse response = new ModelResponse();
            response.setContent("Compacted summary");
            response.setToolCalls(Collections.<ToolCall>emptyList());
            return response;
        }
    }
}
