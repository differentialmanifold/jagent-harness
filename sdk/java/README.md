# Java client SDK

Bring server-side agents to your own runtime. Implement local tools with `ToolDefinition`; the SDK handles communication, tool execution, and result delivery. No Spring, JDBC, model SDK, inbound port, or working directory is required.

The SDK provides two entry points:

| Entry point | Use it when |
| --- | --- |
| `AgentRunner` | A separate frontend submits work to the server, and your application receives and executes it. Includes registration, long polling, cancellation, local records, and recovery. |
| `AgentClient` | Your application starts conversations itself and executes local tools during those conversations. |

Requires Java 8+. Runtime dependencies are `jagent-api` (tool contracts and protocol types) and Jackson. HTTP uses the JDK.

## Install

```xml
<dependency>
  <groupId>io.github.differentialmanifold</groupId>
  <artifactId>jagent-client-java</artifactId>
  <version>1.2.1</version>
</dependency>
```

For the current source changes, run `mvn install` in the repository first. To use coding on a machine without writing an application, use the [coding distribution](../../examples/coding-runner/README.md). Its executable includes this SDK and the coding tools; no separate SDK installation is needed.

## Create your own tool host

Define native tools and pass them to `AgentRunner`. This example has no workspace:

```java
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.client.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import java.nio.file.Paths;
import java.util.Collections;

ToolDefinition status = new ToolDefinition() {
    public String getName() { return "device_status"; }
    public String getDescription() { return "Read this device's status"; }
    public JsonNode getParametersSchema() {
        return new ObjectMapper().createObjectNode().put("type", "object");
    }
    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
        context.getStopSignal().throwIfAborted();
        // Replace with your device API or application operation.
        return ToolExecutionResult.success(Collections.singletonMap("state", "ready"));
    }
};

try (AgentRunner runner = AgentRunner.builder(
        "http://127.0.0.1:18181", Paths.get("device-agent-state"))
        .token(System.getenv("JAGENT_CLIENT_TOKEN"))
        .name("My device")
        .tools(Collections.singletonList(status))
        .onStatus(System.out::println)
        .build()) {
    runner.serve();
}
```

Open the frontend, create a project, and select **My device**. The SDK registers the tool descriptions, receives work, executes your tool implementation, and returns results through the ordinary conversation API. Tool code stays in your application.

`serve()` blocks the calling thread. An existing application can run it on a worker thread and call `close()` during shutdown. The SDK does not install JVM shutdown hooks or terminate the process. A standalone application should connect its shutdown lifecycle to `close()`; the [coding CLI](../../examples/coding-runner/src/main/java/io/github/differentialmanifold/jagentharness/runner/RunnerMain.java) demonstrates this.

The state directory stores the runner identity and execution records; it is not a working directory for tools. One instance owns each state directory. Reuse it to reconnect with the same identity, and use a separate directory for another device or server. The API token is not saved.

## Scenario-specific dependencies

A tool can receive dependencies through its constructor: a robot controller, a phone automation API, a business service, or a file path. Alternatively, pass a process-local object with `AgentRunner.Builder.toolContext(...)` and access it using `context.requireLocalContext(MyDevice.class)`. This object is never serialized.

For file-based scenarios, add `.workspaces(new LocalWorkspaces())` to the builder. The frontend can browse the terminal and choose a directory per project. Each tool receives that job’s resolved directory through `context.getWorkspaceRoot()`. Implement `RunnerWorkspaces` for a custom directory policy, or omit it entirely for workspace-free devices. The server never resolves a remote path on its own filesystem.

Both server and client tools implement `ToolDefinition`. Its `descriptor()` method produces the name, description, and parameter schema sent over the wire. Return structured content with `ToolExecutionResult.success(...)`, `.failure(...)`, or `.unknown(...)`; no coding-specific interface or JSON string adapter is needed. Tools should check the shared `StopSignal` or register a callback to cancel blocking device operations.

## Start conversations from your application

Use `AgentClient` when the application owns the conversation entry point. With the `status` tool above:

```java
import io.github.differentialmanifold.jagentharness.protocol.*;

HttpAgentTransport transport = new HttpAgentTransport(
    "http://127.0.0.1:18181", System.getenv("JAGENT_CLIENT_TOKEN"));
AgentClient client = new AgentClient(transport, Collections.singletonList(status));
ChatRequest request = new ChatRequest();
request.sessionId = transport.createSession("Device assistant", null);
request.messages.add(ChatMessage.user("Check this device"));
request.stream = true;
ChatResponse response = client.run(request,
    new ClientRunOptions().onEvent(event -> System.out.println(event.type)));
```

Reuse `sessionId` for follow-up messages. `run()` executes on the calling thread and may make several HTTP requests before returning. By default it advertises all registered tools; a request can provide a nonempty subset in `clientTools`. `ClientRunOptions.toolContext(...)` supplies local dependencies; `.stopSignal(...)` controls local cancellation. Call `transport.stop(runId)` to stop a server-side operation.

## Cancellation and recovery

`AgentRunner` keeps receiving cancellation while tools execute. It records accepted jobs and tool outcomes before delivery, so a redelivered job does not replay the initial prompt or a tool that already started. A crash before saving the tool result leaves the outcome `UNKNOWN`.

After inspecting the local effects of an interrupted operation, stop the receiver and create a runner with the same server URL, state directory, and tool implementations. The saved job supplies its project workspace and selected catalog. Call `runner.recover(sessionId)` to submit saved results. Recovery does not re-execute pending calls; the server may issue new calls after receiving their results. Restart `serve()` afterwards.

`AgentClient` alone does not keep a durable journal or retry requests automatically. If a connection fails, inspect `transport.state(sessionId)` and the actual tool effects before continuing. Both entry points submit tool results as ordinary `role=tool` messages under the same session.

The current runner supports one active conversation at a time. Projects select enabled tools in the frontend. Each job freezes that selection, and the SDK rejects calls outside it, including when no tools are selected. Selected tools run with Full access under the host process’s permissions. Application lifecycle remains local. Frontends use the server APIs independently of the SDK. Another language can implement the same [HTTP/JSON protocol](../../protocol/README.md#remote-runners) without MCP.
