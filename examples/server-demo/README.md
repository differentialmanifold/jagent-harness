# Server host

A standard JAgentHarness server hosted in a standalone Spring Boot application. It provides model access, SQLite conversation storage, streaming chat and console APIs, built-in tools, and optional MCP support. It is the server component of the coding demo and can also host agents for your own client applications.

The application has its own Spring Boot parent, startup class, and configuration. It contains no business tools or preloaded skills. Copy this directory into your own project and customize it through the framework's extension interfaces.

## Run as part of the coding demo

Follow the root [quick start](../../README.md#try-it-locally) and run `node scripts/dev.mjs` from the repository root. The launcher starts this server together with the coding runner and frontend. Local file and shell tools are built into the runner; model access, conversation storage, skills, and virtual files belong to this server. The frontend calls this server directly wherever it is hosted.

## Build and deploy independently

For local source builds, run `mvn install` in the framework root first. From this directory, or a copy outside the framework repository:

```sh
mvn package
java -jar target/server-demo-2.0.0.jar
```

Provide `JAGENT_MODEL`, `JAGENT_OPENAI_BASE_URL`, and `JAGENT_OPENAI_API_KEY` as process environment variables. Direct JAR launches do not read the framework's `.env.local`.

The application listens on `127.0.0.1:18181` and stores conversations in `agent-server.db` in its working directory. Set `AGENT_BIND_ADDRESS`, `AGENT_SERVER_PORT`, and `JAGENT_CLIENT_TOKEN` for your deployment. Use a persistent writable SQLite location and serve remote access through HTTPS.

## Extend the server

This application hosts the harness for local or remote clients. Use the [server integration guide](../../jagent-harness-sdk/README.md) to configure it, or the [client SDK guide](../../sdk/java/README.md) to connect tools in another application.

SQLite is the only database dependency in this example. Configure `JAGENT_DATASOURCE_URL` to choose its file location. Other database integrations are supported through the JDBC module or storage interfaces; see [Server integration](../../jagent-harness-sdk/README.md#storage). MCP is optional and can be removed from the application's dependencies when unused.

## Personal Agents and Feishu

The demo includes the optional personal Agent application layer. Open **Agents** in the Console to create an assistant, review its memory, track goals, and connect Feishu later. Try the [hands-on TODO example](../personal-agents/README.md) using the Coding Runner and JDK, or see the [Console guide](../../console/README.md#personal-agents).

Personal Agents use the ordinary project and Runner execution mechanism with separate project ownership. Scoped tools with explicit parameter schemas provide access to their own projects, conversations, memory, and tasks. Planning and verification rules are always included in the system instructions. Private Skills hold domain methods and verified lessons; no introductory Skill needs to be loaded.

| Property | Default | Purpose |
| --- | --- | --- |
| `harness.personal.enabled` | `true` | Enable personal Agents when a `PersonalAgentStore` is available. |
| `harness.personal.learning.enabled` | `true` | Enable bounded asynchronous knowledge reviews. Foreground memory/skill editing remains available when disabled. |
| `harness.personal.learning.timeout-ms` | `30000` | Review cancellation deadline, clamped to 1–60 seconds. Model providers must honor `StopSignal`. |
| `harness.personal.feishu.enabled` | `true` | Enable Feishu when the optional official SDK is present. No connection is opened until an account is configured. |

The demo declares `com.larksuite.oapi:oapi-sdk:2.8.7`. Other server applications can add that dependency to enable the same Feishu setup, or omit it and use personal Agents only in the Console. It is never a dependency of the lightweight client SDK.

The JDBC store includes five additive `personal_*` tables. Project ownership uses existing session metadata. Custom session stores should implement creation with `ownerAgentId`, retain ownership across project sessions, and reject conflicting owners. Custom personal stores must save knowledge changes and their revision/history atomically.

Run one personal Agent worker per database application ID. This intentionally small scheduler is designed for one active server process, not distributed workflow scheduling. For deployments with multiple server replicas, keep this feature disabled until a single-worker deployment is provided. There is no model activity while an Agent waits for a message, result, or scheduled check.
