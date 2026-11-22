# Server host

[Documentation](../../docs/README.md) · [Quick start](../../QUICK_START.md)

A standard JAgentHarness server hosted in a standalone Spring Boot application. It provides model access, SQLite or PostgreSQL conversation storage, streaming chat and console APIs, built-in tools, and optional MCP support. It is the server component of the coding demo and can also host agents for your own client applications.

The application has its own Spring Boot parent, startup class, and configuration. It contains no business tools or preloaded skills. Copy this directory into your own project and customize it through the framework's extension interfaces.

## Run as part of the coding demo

Follow the root [quick start](../../QUICK_START.md) and run `node scripts/dev.mjs` from the repository root. The launcher starts this server together with the coding runner and frontend. Local file and shell tools are built into the runner; model access, conversation storage, skills, and virtual files belong to this server. The frontend calls this server directly wherever it is hosted.

## Build and deploy independently

For local source builds, run `mvn install` in the framework root first. From this directory, or a copy outside the framework repository:

```sh
mvn package
java -jar target/server-demo-2.4.0.jar
```

Provide `JAGENT_MODEL`, `JAGENT_OPENAI_BASE_URL`, and `JAGENT_OPENAI_API_KEY` as process environment variables. Direct JAR launches do not read the framework's `.env.local`.

The application listens on `127.0.0.1:18181` and by default stores conversations in `agent-server.db` in its working directory. Set `AGENT_BIND_ADDRESS` and `AGENT_SERVER_PORT` for your deployment. The first startup writes local credentials to `data/user.env`; issue other users with the [local user commands](../../docs/guides/access.md). Use a persistent writable SQLite location. HTTP works with a warning; HTTPS is recommended for remote access.

## Extend the server

This application hosts the harness for local or remote clients. Use the [server integration guide](../../jagent-harness-sdk/README.md) to configure it, or the [client SDK guide](../../sdk/java/README.md) to connect tools in another application.

The packaged host includes SQLite and PostgreSQL JDBC drivers. Set the JDBC URL and credentials to use PostgreSQL; the same selection applies to the server and its local user commands. SQLite defaults to five pooled connections and a five-second lock wait per connection. [Database setup](../../QUICK_START.md#choose-a-database) · [Server integration](../../jagent-harness-sdk/README.md#storage).

MCP is optional and can be removed from the application's dependencies when unused.

## Personal Agents and Feishu

This host enables Personal Agents and includes the optional Feishu SDK. Follow the [Agent guide](../../docs/guides/agents.md) to try a goal, or the [Feishu guide](../../docs/guides/feishu.md) to connect a channel later.

For feature flags, learning settings and storage requirements in your own host, see [Personal Agent settings](../../jagent-harness-sdk/README.md#personal-agent-settings). The demo is intended for one active server process per database application ID.

For additional browsers or headless first access, see [device access and operator links](../../docs/guides/access.md#first-access-without-a-desktop-runner).
