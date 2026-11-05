package io.github.differentialmanifold.jagentharness.core.message;

import static org.junit.jupiter.api.Assertions.*;

import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import java.util.*;
import org.junit.jupiter.api.Test;

class FailedTurnTest {
    @Test
    void preservesSuccessfulToolsWhenTheFollowingModelCallFails() {
        AgentMessage user = AgentMessage.user("session", "refund");
        user.setRunId("run");
        user.setTurnId("first");
        AgentMessage assistant =
                AgentMessage.assistant(
                        "session",
                        "",
                        Collections.singletonList(new ToolCall("call", "refund", "{}")));
        assistant.setRunId("run");
        assistant.setTurnId("first");
        AgentMessage tool = AgentMessage.tool("session", "call", "refund", "succeeded");
        tool.setRunId("run");
        tool.setTurnId("first");
        AgentMessage steering = AgentMessage.user("session", "new invalid input");
        steering.setRunId("run");
        steering.setTurnId("second");
        List<AgentMessage> history =
                new ArrayList<>(Arrays.asList(user, assistant, tool, steering));
        history.add(FailedTurn.receipt(history, "session", "run", "second", "provider error"));
        assertEquals(Arrays.asList(user, assistant, tool), FailedTurn.context(history));
        assertTrue(ConversationHistory.pendingTools(FailedTurn.context(history)).isEmpty());
    }

    @Test
    void doesNotExcludeAnAcceptedInputWhenLaterWorkInItsTurnFails() {
        AgentMessage user = AgentMessage.user("session", "hello");
        user.setRunId("run");
        user.setTurnId("turn");
        AgentMessage answer = AgentMessage.assistant("session", "answer", Collections.emptyList());
        answer.setRunId("run");
        answer.setTurnId("turn");
        List<AgentMessage> history = new ArrayList<>(Arrays.asList(user, answer));
        history.add(FailedTurn.receipt(history, "session", "run", "turn", "later error"));
        assertEquals(Arrays.asList(user, answer), FailedTurn.context(history));
    }
}
