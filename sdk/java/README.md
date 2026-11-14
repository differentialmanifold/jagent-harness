# Java client SDK

[Documentation](../../docs/README.md) · [Coding Runner](../../examples/coding-runner/README.md)

Bring server-side agents to your own runtime. Implement local tools with `ToolDefinition`; the SDK handles communication, tool execution, and result delivery. No Spring, JDBC, model SDK, inbound port, or working directory is required.

Use `AgentRunner` to register native tools and receive work from the server. Run it as a standalone process or embed it in an existing application's lifecycle. The frontend or an external Agent submits work through the server APIs; project configuration selects the execution terminal.

Requires Java 8+. Runtime dependencies are `jagent-api` (tool contracts and protocol types) and Jackson. HTTP uses the JDK.

## Install

```xml
<dependency>
  <groupId>io.github.differentialmanifold</groupId>
  <artifactId>jagent-client-java</artifactId>
  <version>2.2.1</version>
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

Open the frontend, create a project, and select **My device**. The SDK registers the tool descriptions, receives work, executes your tool implementation, and returns results through the authenticated internal `/api/v1/runners/chat` protocol. Frontends submit work through `/api/v1/chat`. Tool code stays in your application.

`serve()` blocks the calling thread. An existing application can run it on a worker thread and call `close()` during shutdown. The SDK does not install JVM shutdown hooks or terminate the process. A standalone application should connect its shutdown lifecycle to `close()`; the [coding CLI](../../examples/coding-runner/src/main/java/io/github/differentialmanifold/jagentharness/runner/RunnerMain.java) demonstrates this.

The path passed to `builder` is a state root, not a working directory for tools. Each server URL automatically gets its own `servers/<SHA-256 of server URL>/` directory containing the runner identity, execution records, and lock. A trailing slash is ignored. Reconnecting to the same URL reuses its identity; different URLs can run concurrently under one root. Use a separate root for another device. The API token is not saved. `AgentRunner.stateDirectory(server, root)` resolves the directory for tools that also persist local state.

For compatibility, an existing `config.json` directly under the root remains in use only for its original server (unless a scoped identity already exists). Its journals and background jobs stay at their original paths. Other servers automatically use isolated subdirectories, without changing the legacy identity.

## Scenario-specific dependencies

A tool can receive dependencies through its constructor: a robot controller, a phone automation API, a business service, or a file path. Alternatively, pass a process-local object with `AgentRunner.Builder.toolContext(...)` and access it using `context.requireLocalContext(MyDevice.class)`. This object is never serialized.

For file-based scenarios, add `.workspaces(new LocalWorkspaces())` to the builder. The frontend can browse the terminal and choose a directory per project. Each tool receives that job’s resolved directory through `context.getWorkspaceRoot()`. Implement `RunnerWorkspaces` for a custom directory policy, or omit it entirely for workspace-free devices. The server never resolves a remote path on its own filesystem.

Both server and client tools implement `ToolDefinition`. Its `descriptor()` method produces the name, description, and parameter schema sent over the wire. Return structured content with `ToolExecutionResult.success(...)`, `.failure(...)`, or `.unknown(...)`; no coding-specific interface or JSON string adapter is needed. Tools should check the shared `StopSignal` or register a callback to cancel blocking device operations.

## Cancellation and recovery

`AgentRunner` keeps receiving cancellation while tools execute. It records accepted jobs and tool outcomes before delivery, so a redelivered job does not replay the initial prompt or a tool that already started. A crash before saving the tool result leaves the outcome `UNKNOWN`.

After inspecting the local effects of an interrupted operation, stop the receiver and create a runner with the same server URL, state directory, and tool implementations. The saved job supplies its project workspace and selected catalog. Call `runner.recover(sessionId)` to submit saved results. Recovery does not re-execute pending calls; the server may issue new calls after receiving their results. Restart `serve()` afterwards.

Tool results are submitted as ordinary `role=tool` messages through the authenticated Runner protocol under the same session and claimed run. The SDK does not expose a separate conversation client. To build a frontend, use the public [server APIs](../../protocol/README.md); to delegate to a Personal Agent, use the [external Agent Skill](../../docs/guides/external-agents.md).

A runner executes independent conversations concurrently without a fixed conversation limit. Each session still runs one job at a time; cancelling a run affects only that run. Tool implementations, local context, and event listeners are shared across runs and must support concurrent use. Projects select enabled tools in the frontend. Each job freezes that selection, and the SDK rejects calls outside it, including when no tools are selected. Selected tools run with Full access under the host process’s permissions. Application lifecycle remains local. Frontends use the server APIs independently of the SDK. Another language can implement the same [HTTP/JSON protocol](../../protocol/README.md#remote-runners) without MCP.

With `new LocalWorkspaces()`, projects without an explicit directory use
`~/.jagent/workspaces/user/project_…` or
`~/.jagent/workspaces/agents/agent_…/project_…` on the Runner. Directories are
created on first execution. Use `new LocalWorkspaces(path)` to choose another
root. Workspace-free Runners remain supported.

## Describe an execution environment

Use `.description("Linux training host with a GPU")`, `.capabilities(Arrays.asList("training", "linux"))`, and optionally `.platform("Linux x86_64")` on `AgentRunner.builder(...)`. The SDK otherwise reports the OS name and architecture. Agents use these descriptions to discover a suitable colleague and Runner; actual tools must verify environment details.

A Runner need not expose a filesystem. A mobile application can implement native tools and the same outbound HTTP protocol with `workspaces=false`. The current Java SDK is not a ready-made Android/iOS integration: app lifecycle, background suspension, permissions and tool implementations belong to the host app. Jobs can wait while the terminal is offline. Return screenshot evidence with `ToolExecutionResult.success(content).withImages(...)`; each image map uses `name`, `mediaType`, `url` (a supported image data URL) and optional `detail`. Implementations in other languages use the same JSON fields, without MCP.
