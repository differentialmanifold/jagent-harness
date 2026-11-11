package io.github.differentialmanifold.jagentharness.core.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.message.*;
import io.github.differentialmanifold.jagentharness.core.tool.ToolCall;
import java.util.*;
import org.junit.jupiter.api.Test;

class RepeatedToolFailureDetectorTest {
    final List<AgentMessage> history = new ArrayList<>();
    final RepeatedToolFailureDetector detector =
            new RepeatedToolFailureDetector(new ObjectMapper());

    @Test
    void countsCompletedBatchesNotParallelCallsOrPartialResults() {
        for (int batch = 0; batch < 3; batch++) {
            List<AgentMessage> results = batch(4, "FAILED", "{}");
            history.addAll(results.subList(0, 3));
            assertEquals(RepeatedToolFailureDetector.Decision.CONTINUE, detector.inspect(history));
            history.add(results.get(3));
        }
        assertEquals(RepeatedToolFailureDetector.Decision.REMIND, detector.inspect(history));
        history.add(AgentMessage.runtimeContext("s", RepeatedToolFailureDetector.REMINDER));
        assertEquals(RepeatedToolFailureDetector.Decision.CONTINUE, detector.inspect(history));
        List<AgentMessage> results = batch(4, "FAILED", "{}");
        Collections.reverse(results);
        history.addAll(results);
        assertEquals(RepeatedToolFailureDetector.Decision.STOP, detector.inspect(history));
    }

    @Test
    void ignoresJsonKeyOrderButResetsForSuccessChangedResultsOrUserSteering() {
        for (String reset : Arrays.asList("success", "changed", "user")) {
            history.clear();
            history.addAll(batch(1, "FAILED", "{\"a\":1,\"b\":2}"));
            history.addAll(batch(1, "FAILED", "{ \"b\":2, \"a\":1 }"));
            history.addAll(batch(1, "FAILED", "{\"a\":1,\"b\":2}"));
            assertEquals(RepeatedToolFailureDetector.Decision.REMIND, detector.inspect(history));
            history.add(AgentMessage.runtimeContext("s", RepeatedToolFailureDetector.REMINDER));
            if ("user".equals(reset))
                history.add(AgentMessage.user("s", "I fixed the permission; try again"));
            else
                history.addAll(
                        batch(1, "success".equals(reset) ? "SUCCEEDED" : "FAILED", "{\"a\":3}"));
            history.addAll(batch(1, "FAILED", "{\"a\":1,\"b\":2}"));
            assertEquals(RepeatedToolFailureDetector.Decision.CONTINUE, detector.inspect(history));
        }
    }

    List<AgentMessage> batch(int size, String status, String args) {
        List<ToolCall> calls = new ArrayList<>();
        List<AgentMessage> results = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            String id = UUID.randomUUID().toString();
            calls.add(new ToolCall(id, "read", args));
            AgentMessage result = AgentMessage.tool("s", id, "read", "Permission denied");
            ToolOutcome.record(result, status);
            results.add(result);
        }
        history.add(AgentMessage.assistant("s", "", calls));
        return results;
    }
}
