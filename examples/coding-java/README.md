# Coding client example

A Java application that connects to a JAgentHarness server and executes tools in a local workspace. It provides file reading, writing, editing, directory listing, search, and shell execution, with local approval and cancellation controls. It is a working example of [Harness and execution separation](../../README.md#how-it-works): the cloud server decides what to do, this application decides what may run on this machine.

## Run

Configure `.env.local` using the root [quick start](../../README.md#try-it-locally), then run:

```sh
node scripts/dev.mjs
```

The launcher starts `server-demo`, this coding client, and the console. Open **http://127.0.0.1:5175**, create a project with a local workspace path, and start a conversation. Try “List the files in this project and summarize its README.” Use a scratch workspace to experiment with writes and shell commands.

To connect to an existing server, configure `JAGENT_SERVER_URL` and `JAGENT_CLIENT_TOKEN`, then add `--client-only`. Model credentials are only needed by the server.

## Build your own client

The application uses [jagent-client-java](../../sdk/java/README.md). Its `CodingTool` interface extends the shared `ToolDefinition`, and `CodingContext` supplies the local workspace, stop signal, and approval handler. File tools include UTF-8 validation, atomic writes, edit-conflict detection, and workspace path checks. Search uses ripgrep when available, with a Java fallback.

`CodingGateway` exposes the API used by the shared console. It invokes the SDK, forwards conversation events, and proxies server management APIs. Tool execution and approval remain local. You can replace the gateway and console with your own UI while using the same SDK.

The application JAR is `target/jagent-coding-java-1.1.0.jar`. It listens on `127.0.0.1:18180` by default; configure `CODING_CLIENT_PORT`, `JAGENT_SERVER_URL`, and `JAGENT_CLIENT_TOKEN` for standalone use. Direct JAR launches read process environment variables, not `.env.local`.
