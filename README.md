# JAgentHarness

Embeddable Java agent harness SDK for server-side applications.

JAgentHarness is designed to run inside an application process, not as a central service that hosts unrelated agents. The host application registers tools, skills, prompts, model providers, and storage providers, then calls the harness from normal Java or Spring Boot code.

This repository also includes coding and business-system examples with Spring Boot backends and a shared Vue console UI.

## Features

- Java 8 and Maven based SDK.
- Agent loop for model calls, tool calls, tool results, and follow-up model calls.
- Tools are Java methods, not command-line wrappers.
- Spring Bean extension points for tools, model providers, skill providers, storage, and custom events.
- OpenAI-compatible chat completions provider.
- JDBC-backed session, message, timeline event, prompt, and skill storage.
- Virtual knowledge filesystem for database-backed prompt and skill files.
- Database-scoped `AGENTS.md` loading for global and project instructions.
- Database-scoped skills with project-over-global precedence.
- Java 8 Streamable HTTP MCP client with global and project database configuration.
- Optional Console Spring Boot starter with `/api/*` endpoints and SSE streaming for realtime UIs.
- Context compaction when a conversation approaches the model context window.

## Repository Layout

```text
JAgentHarness/
  pom.xml                         Development reactor for SDK modules and examples
  jagent-harness-sdk/
    pom.xml                       SDK reactor for publishable Maven modules
    jagent-core/                  Core SDK interfaces and agent runtime
    jagent-mcp-client/            Framework-neutral Streamable HTTP MCP client
    jagent-mcp-spring-boot-starter/
                                  MCP configuration and Spring Boot lifecycle
    jagent-spring-boot-starter/   Spring Boot auto-configuration for embedded agent runtime
    jagent-console-spring-boot-starter/
                                  Optional backend console API for the Vue UI
    jagent-store-jdbc/            JDBC session and timeline store
  examples/
    coding-tool-app/              Spring Boot coding-agent example backend
    business-system-agent-demo/   Business-system SDK embedding example
    business-system-agent-console-demo/
                                  Business-system example backend with Console API
  frontend/                       Vue console UI for Console Spring Boot examples
  plan/                           Design notes
```

## Requirements

- Java 8
- Maven 3.x
- Node.js and npm, only for the Vue example UI

## Quick Start

See [QUICK_START.md](./QUICK_START.md).

Short version:

```bash
export JAGENT_OPENAI_API_KEY=your_api_key
mvn -f pom.xml -pl examples/coding-tool-app -am package -DskipTests
java -jar examples/coding-tool-app/target/coding-tool-app-0.5.0.jar
```

In another terminal:

```bash
cd frontend
npm install
npm run dev:coding
```

Open `http://localhost:5173`.

### Stop a streamed run

The backend assigns a transport-level `requestId` to each streamed chat request and returns it in
the `X-Request-Id` response header:

```json
{
  "sessionId": "session-id",
  "content": "Inspect the project"
}
```

Stop that request without exposing the agent's internal `turnId`:

```bash
curl -X POST \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"req_1234567890abcdef"}' \
  http://localhost:18080/api/chat/requests/stop
```

The frontend reads the backend-generated request ID from the response header, keeps the SSE
connection open until it receives `agent_stopped`, and uses `AbortController` only as a timeout
fallback.
Active runs are coordinated through `RunStopCoordinator`. The JDBC store provides the default
implementation and records each request in the shared database, so a stop request can reach a run
owned by another service instance. Each active request watches only its own database row; there is
no full-table polling. Rows are retained after completion: the status starts as `NORMAL` and changes
to `STOP_REQUESTED` only when the stop endpoint is called. Backend-generated request IDs are not
reused. A custom coordinator bean can replace JDBC with Redis or another transport.

## Spring Boot Usage

Add the modules your application needs:

```xml
<properties>
    <jagent-harness.version>0.5.0</jagent-harness.version>
</properties>

<dependencies>
    <dependency>
        <groupId>io.github.differentialmanifold</groupId>
        <artifactId>jagent-spring-boot-starter</artifactId>
        <version>${jagent-harness.version}</version>
    </dependency>
    <dependency>
        <groupId>io.github.differentialmanifold</groupId>
        <artifactId>jagent-store-jdbc</artifactId>
        <version>${jagent-harness.version}</version>
    </dependency>
    <!-- Add the JDBC driver used by your spring.datasource.* configuration. -->
    <dependency>
        <groupId>org.xerial</groupId>
        <artifactId>sqlite-jdbc</artifactId>
        <version>3.45.3.0</version>
    </dependency>
</dependencies>
```

This dependency set does not expose any HTTP API. It is for applications that call the agent from their own Java services.
The Spring Boot starter includes the default OpenAI-compatible provider; add a custom `ModelProvider` bean for another provider.
The JDBC store reuses the host application's Spring Boot `DataSource`; configure it with standard `spring.datasource.*` properties.
When multiple host applications share one database, set a distinct `harness.store.jdbc.application-id` for each application so sessions, messages, prompt files, skills, approvals, and stop requests stay isolated.
Its schema is published as `db/jagent-harness/schema.sql` inside `jagent-store-jdbc`, so host applications can run the same SQL in their own database migration process.
The schema includes the virtual knowledge filesystem, skill manifest, and active
agent run tables. Multi-instance deployments must point every instance at the same database;
the default SQLite configuration is intended for local single-host development.

Add the console starter only when you want the bundled Vue console or the `/api/*` management endpoints:

```xml
<dependency>
    <groupId>io.github.differentialmanifold</groupId>
    <artifactId>jagent-console-spring-boot-starter</artifactId>
    <version>${jagent-harness.version}</version>
</dependency>
```

Add the MCP starter when the agent needs tools from remote Streamable HTTP MCP servers:

```xml
<dependency>
    <groupId>io.github.differentialmanifold</groupId>
    <artifactId>jagent-mcp-spring-boot-starter</artifactId>
    <version>${jagent-harness.version}</version>
</dependency>
```

Configure servers in the database-backed global or project `mcp.json` managed by the Console.
Project servers override global servers with the same name:

```json
{
  "mcpServers": {
    "catalog": {
      "transport": "streamable-http",
      "url": "https://example.com/mcp",
      "enabled": true,
      "enabledTools": ["product_search", "inventory_check"],
      "headers": {
        "Authorization": "Bearer ${CATALOG_MCP_TOKEN}"
      },
      "connectTimeoutSeconds": 10,
      "requestTimeoutSeconds": 60
    }
  }
}
```

Sensitive headers must reference environment variables. MCP tools are exposed to the model as
`serverName__toolName`. Omitting `enabledTools` loads every discovered tool; an empty array loads
none. Global and project configuration changes are detected from the database and applied on the
next agent run.

Use the harness from application code:

```java
@Service
public class AgentService {
    private final AgentHarness agentHarness;

    public AgentService(AgentHarness agentHarness) {
        this.agentHarness = agentHarness;
    }

    public AgentRunResult run(String sessionId, String userText) {
        AgentRunOptions options = AgentRunOptions.builder()
                .traceId("trace-123")
                .attribute("channel", "web")
                .build();

        return agentHarness.run(sessionId, userText, options);
    }
}
```

## Tools and Extensions

Tools implement `ToolDefinition`:

```java
@Component
public class MyTool implements ToolDefinition {
    public String getName() {
        return "my_tool";
    }

    public String getDescription() {
        return "Call a business capability.";
    }

    public JsonNode getParametersSchema() {
        return schema;
    }

    public ToolExecutionResult execute(ToolContext context, JsonNode arguments) {
        return ToolExecutionResult.of("{}");
    }
}
```

Extensions are Spring beans. The starter collects `ToolDefinition`, `ModelProvider`, `SkillProvider`,
`PromptProvider`, `SessionStore`, and `AgentEventListener` beans and wires them into the runtime.
Reusable extension modules should expose those beans through Spring Boot auto-configuration.
The default OpenAI-compatible provider uses an OkHttp-backed `ModelHttpClient`; applications can
override it by registering their own `ModelHttpClient` bean.

The SDK provides the built-in `skill` tool for loading `SKILL.md` instructions and files referenced
by a skill. The coding-tool example registers workspace-specific tools as Spring beans:
`bash`, `read`, `edit`, `write`, `grep`, `find`, and `ls`. Its `read` tool only accesses the
workspace and supports one-based `offset` and line-count `limit` arguments.

JAgentHarness uses a built-in general system prompt that cannot be replaced by `SYSTEM.md`.
It appends the global and current project `AGENTS.md` stored in the database. Skills use normal
paths such as `skills/review/SKILL.md` and are merged by name with project scope taking precedence
over global scope. The built-in `skill` tool resolves the same logical path from project scope first,
then global scope. Runtime prompt, skill, and MCP loading does not scan the external filesystem.

## Configuration

Common environment variables used by the example applications:

| Variable | Example Default | Description |
| --- | --- | --- |
| `JAGENT_OPENAI_API_KEY` | empty | Optional API key for the OpenAI-compatible provider. When set, it is sent as a Bearer token. |
| `JAGENT_OPENAI_BASE_URL` | `https://open.bigmodel.cn/api/coding/paas/v4` | Provider base URL configured by the example `application.yml`. |
| `JAGENT_MODEL` | `glm-5.2` | Model name configured by the example `application.yml`. |
| `JAGENT_MODEL_STREAM_ENABLED` | `true` | Set to `false` for OpenAI-compatible endpoints that do not support SSE streaming. |
| `JAGENT_TEMPERATURE` | empty | Optional. If empty, `temperature` is not sent. |
| `SERVER_PORT` | `18080` | HTTP port used by the example applications. |
| `JAGENT_DATASOURCE_URL` | `jdbc:sqlite:jagent-harness.db` | JDBC URL used by the examples. |
| `JAGENT_DATASOURCE_DRIVER` | `org.sqlite.JDBC` | JDBC driver class used by the examples. |
| `JAGENT_DATASOURCE_USERNAME` | empty | JDBC username, when needed. |
| `JAGENT_DATASOURCE_PASSWORD` | empty | JDBC password, when needed. |
| `JAGENT_STOP_POLL_INTERVAL_MS` | `1000` | Interval for each active request to check its own stop row. |
| `JAGENT_STOP_LISTENER_THREADS` | `2` | Shared scheduler threads used by the per-request stop listeners. |
| `JAGENT_CORS_ORIGIN` | `http://localhost:5173` | Console UI CORS origin. Used only by the console starter. |
| `JAGENT_CORS_ORIGIN_127` | `http://127.0.0.1:5173` | Additional console UI CORS origin for loopback access. |
| `JAGENT_COMPACTION_ENABLED` | `true` | Enable context compaction. |
| `JAGENT_CONTEXT_WINDOW_TOKENS` | `128000` | Model context window used for compaction checks. |

## Development

Run backend tests:

```bash
mvn -f pom.xml test
```

Build the Vue UI:

```bash
cd frontend
npm run build
```

The root `pom.xml` is for local development. Importing it in an IDE lets examples use SDK modules directly from the Maven reactor without installing them into the local Maven repository.

## License

This project is licensed under the [MIT License](./LICENSE).
