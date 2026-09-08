# Java client SDK

Connect a Java application to a JAgentHarness server and let the agent use tools inside that application. The SDK runs client tools, submits their results, and streams events through a single application-facing call. This is the device-side half of [Harness and execution separation](../../README.md#how-it-works): the server plans and reasons, your application executes, and conversation messages, tool descriptions, calls, and results cross the network.

Requires Java 8+. Runtime dependencies are `jagent-api` (tool contracts and protocol types) and Jackson. HTTP uses the JDK; Spring, JDBC, and model clients are not required.

## Install

```xml
<dependency>
  <groupId>io.github.differentialmanifold</groupId>
  <artifactId>jagent-client-java</artifactId>
  <version>1.0.0</version>
</dependency>
```

When building against a local source checkout, run `mvn install` in the framework repository first.

## Register a tool and start a conversation

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.client.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import io.github.differentialmanifold.jagentharness.protocol.*;
import java.util.Collections;

HttpAgentTransport transport = new HttpAgentTransport(
    "http://127.0.0.1:18181", System.getenv("JAGENT_CLIENT_TOKEN"));
ToolDefinition lookup = new ToolDefinition() {
    public String getName() { return "business_lookup"; }
    public String getDescription() { return "Look up local business information"; }
    public JsonNode getParametersSchema() {
        return new ObjectMapper().createObjectNode().put("type", "object");
    }
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
        context.getStopSignal().throwIfAborted();
        return ToolExecutionResult.success(Collections.singletonMap("value", "local data"));
    }
};
AgentClient client = new AgentClient(transport, Collections.singletonList(lookup));
ChatRequest request = new ChatRequest();
request.sessionId = transport.createSession("Business assistant", null);
request.messages.add(ChatMessage.user("Look up business information"));
request.stream = true;
ChatResponse result = client.run(request,
    new ClientRunOptions().onEvent(event -> System.out.println(event.type)));
System.out.println(result.status + ": " + result.answer);
```

Reuse `sessionId` for follow-up messages. `AgentClient.run` executes on the calling thread and may make several HTTP requests before returning. Schedule it on a worker thread when integrating with a UI. The application owns tool permissions and lifecycle.

## Tool context and results

Both server and client tools implement `ToolDefinition`. Its `descriptor()` method produces the name, description, and parameter schema sent to the server. The implementation stays local. By default the SDK advertises all registered tools; a request can instead provide a nonempty subset in `clientTools`. Either way, the server sees only the descriptors and returns tool calls for your application to execute — it never needs to reach back into the device.

Pass a local business object with `new ClientRunOptions().toolContext(localObject)`. Tools retrieve it through `context.requireLocalContext(MyContext.class)`. Workspaces, service objects, and approval handlers are not transmitted to the server.

Return `ToolExecutionResult.success(value)`, `failure(value)`, or `unknown(value)`. UNKNOWN means the execution outcome is uncertain. Exceptions are reported as UNKNOWN because an exception alone cannot prove that no side effect occurred.

## Events, cancellation, and failures

Use `onEvent` for streaming progress. Keep callbacks short and avoid throwing exceptions. The SDK connects multiple tool exchanges into one conversation lifecycle for the UI.

Supply a `StopSignal` with `ClientRunOptions.stopSignal(...)` to stop local execution. Tools can check the signal or register a callback. Call `transport.stop(runId)` to cancel a server-side operation. The [coding example](../../examples/coding-java/README.md) coordinates both sides and implements local approvals.

The SDK does not automatically retry HTTP requests or replay tool execution. If a connection fails, inspect `transport.state(sessionId)`, conversation history, and the tool's actual effects before deciding how to proceed. Known tool results can be submitted using ordinary `role=tool` messages through the [chat protocol](../../protocol/README.md).
