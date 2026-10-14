# Coding runner

**English** | [简体中文](README.zh-CN.md)

A headless Java process that brings coding tools to the machine containing your workspace. The executable includes `read`, `ls`, `find`, `grep`, `write`, `edit`, and `bash`. It requires Java 8+ and contains no Spring, database driver, model SDK, or web server.

The [Harness server](../../examples/server-demo/README.md) owns the agent loop, model, state, and server tools such as skills. The [frontend](../../console/README.md) talks directly to that server. The runner advertises its built-in tools, receives work over outbound HTTP long polling, executes locally, and returns results. Tool implementations are distributed with the runner, never downloaded from the Harness.

This is a ready-to-run distribution of the [Java client SDK](../../sdk/java/README.md) plus coding tools. Its CLI supplies coding configuration; the SDK owns registration, polling, cancellation, and recovery.

## Connect a machine

For local development, run `RunnerMain.main()` in your IDE or run the JAR without arguments. It starts immediately and connects to `http://127.0.0.1:18181` by default. `JAGENT_SERVER_URL` or `--server` selects another server. `--help` displays usage; `recover` is only for interrupted operations, not a second startup step.

Build from the repository root, or use a release containing the coding runner:

```sh
mvn -pl examples/coding-runner -am package
```

Copy `examples/coding-runner/target/jagent-coding-runner-1.2.0.jar` to the target machine. Set `JAGENT_CLIENT_TOKEN` to the server's API token, then:

```sh
java -jar jagent-coding-runner-1.2.0.jar --server https://agent.example.com --name linux-dev
```

Open the web frontend wherever it is hosted, add a project, and choose **linux-dev**. Browse or enter a directory on that terminal. One runner can execute work for multiple projects with different directories.

New projects enable all seven tools by default. Project tools can be changed in **Configuration → Built-in tools → Client tools**; changes apply to the next run. Selected tools use **Full access**, without interactive approval. A project can disable every client tool.

Tools run with the OS account's file and process permissions; the workspace is a working directory, not an OS sandbox. Use an account or container with suitable permissions. Bash needs a local shell; Windows requires Git Bash. Search uses ripgrep when available and falls back to Java. `JAGENT_RG_PATH` can select a ripgrep executable. The runner logs the detected search backend at startup; **Configuration → Built-in tools → Client tools** shows that startup snapshot beside `find` and `grep`. Each search result reports the backend actually used, including any later fallback.

No model key is needed on this machine. The runner listens on no port. Use HTTPS except for loopback development. A process supervisor can run `serve` unattended; Ctrl+C stops it.

## Local tools and other runners

The built-in implementations live in [tools/coding](src/main/java/io/github/differentialmanifold/jagentharness/tools/coding), with registration in [CodingTools](src/main/java/io/github/differentialmanifold/jagentharness/tools/coding/CodingTools.java). Java tools implement `ToolDefinition`; the [Java SDK](../../sdk/java/README.md) hosts the tools and handles their complete execution lifecycle. Coding tools directly use the shared `ToolDefinition`, `ToolContext`, and cancellation APIs. To add a tool, implement it in the runner and register it there, then rebuild and distribute the runner executable. Finish outstanding jobs before updating.

For another Java scenario, depend on `jagent-client-java`, implement `ToolDefinition`, and register your tools with `AgentRunner`. You do not need to copy this CLI or write registration, polling, or recovery code. A workspace is optional in the SDK; it is required only by these coding tools. Another language can implement its own tool interfaces and the same [HTTP/JSON protocol](../../protocol/README.md#remote-runners), without MCP. The server receives names, descriptions, argument schemas, and execution results—not tool code. Server-side capabilities such as skills remain server tools.

The current frontend is web-based. A desktop or other frontend can use the same server APIs; it does not need a local gateway or to load the runner's tools.

## State, cancellation and recovery

Local state defaults to `~/.jagent-runner`. Use `--home <directory>` or `JAGENT_RUNNER_HOME` for another identity. One home represents one terminal and supports one running process; do not clone it onto another machine. The API token is read from the environment and not saved. The runner secret and execution journal are stored locally.

The server persists registrations, session bindings, and jobs in its database. Long polls wait up to 25 seconds. A receiver remains active during tool execution so cancellation from the frontend can stop local processes. Closing a browser does not cancel execution. User messages and tool results still use the normal conversation API.

Jobs can be delivered again after a disconnect. The runner journals acceptance before execution and saves tool results before returning them. It does not automatically replay the initial prompt or a tool that has already started. If the process dies before saving a result, the outcome is `UNKNOWN`.

To recover pending results after interruption, stop `serve`, inspect the conversation and local effects, and explicitly submit the saved results:

```sh
java -jar jagent-coding-runner-1.2.0.jar recover --server https://agent.example.com --session ses_example
```

Use the same home and executable version. The saved job supplies its project workspace and tool selection. Missing or unfinished results are reported as `UNKNOWN`; the model may issue new calls after receiving them. Restart `serve` afterwards.

This implementation supports one active conversation per runner and one server instance for live frontend streaming. Jobs and histories survive a server restart; streams must reconnect. Multi-instance live-event distribution and multi-tenant credentials are not implemented. The shared API token is an operator credential.
