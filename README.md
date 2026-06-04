# JAgentHarness

Embeddable Java agent harness SDK for server-side applications.

JAgentHarness is designed to run inside an application process, not as a central service that hosts unrelated agents. The host application registers tools, skills, prompts, model providers, and storage providers, then calls the harness from normal Java or Spring Boot code.

This repository also includes a coding-tool example with a Spring Boot backend and a Vue UI.

## Features

- Java 8 and Maven based SDK.
- Agent loop for model calls, tool calls, tool results, and follow-up model calls.
- Tools are Java methods, not command-line wrappers.
- Spring Bean extension points for tools, model providers, skill providers, storage, and custom events.
- OpenAI-compatible chat completions provider.
- JDBC-backed session, message, and timeline event store.
- Prompt and skill loading from application providers or files.
- Optional Console Spring Boot starter with `/api/*` endpoints and SSE streaming for realtime UIs.
- Context compaction when a conversation approaches the model context window.

## Repository Layout

```text
JAgentHarness/
  pom.xml                         Development reactor for SDK modules and examples
  jagent-harness-sdk/
    pom.xml                       SDK reactor for publishable Maven modules
    jagent-core/                  Core SDK interfaces and agent runtime
    jagent-spring-boot-starter/   Spring Boot auto-configuration for embedded agent runtime
    jagent-console-spring-boot-starter/
                                  Optional backend console API for the Vue UI
    jagent-store-jdbc/            JDBC session and timeline store
  examples/
    coding-tool-app/              Spring Boot coding-agent example backend
    order-system-agent/           Business-system embedding example
  frontend/                       Vue example UI for coding-tool-app
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
java -jar examples/coding-tool-app/target/coding-tool-app-0.1.1.jar
```

In another terminal:

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`.

## Spring Boot Usage

Add the modules your application needs:

```xml
<properties>
    <jagent-harness.version>0.1.1</jagent-harness.version>
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
Its schema is published as `db/jagent-harness/schema.sql` inside `jagent-store-jdbc`, so host applications can run the same SQL in their own database migration process.

Add the console starter only when you want the bundled Vue console or the `/api/*` management endpoints:

```xml
<dependency>
    <groupId>io.github.differentialmanifold</groupId>
    <artifactId>jagent-console-spring-boot-starter</artifactId>
    <version>${jagent-harness.version}</version>
</dependency>
```

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

The SDK provides the built-in `read` tool for file-based skills. The coding-tool example registers
coding-specific tools as Spring beans: `bash`, `edit`, `write`, `grep`, `find`, and `ls`.

## Configuration

Common environment variables used by the example applications:

| Variable | Example Default | Description |
| --- | --- | --- |
| `JAGENT_OPENAI_API_KEY` | empty | Optional API key for the OpenAI-compatible provider. When set, it is sent as a Bearer token. |
| `JAGENT_OPENAI_BASE_URL` | `https://open.bigmodel.cn/api/coding/paas/v4` | Provider base URL configured by the example `application.yml`. |
| `JAGENT_MODEL` | `glm-5.1` | Model name configured by the example `application.yml`. |
| `JAGENT_MODEL_STREAM_ENABLED` | `true` | Set to `false` for OpenAI-compatible endpoints that do not support SSE streaming. |
| `JAGENT_TEMPERATURE` | empty | Optional. If empty, `temperature` is not sent. |
| `JAGENT_DATASOURCE_URL` | `jdbc:sqlite:jagent-harness.db` | JDBC URL used by the examples. |
| `JAGENT_DATASOURCE_DRIVER` | `org.sqlite.JDBC` | JDBC driver class used by the examples. |
| `JAGENT_DATASOURCE_USERNAME` | empty | JDBC username, when needed. |
| `JAGENT_DATASOURCE_PASSWORD` | empty | JDBC password, when needed. |
| `JAGENT_CONFIG_ROOT` | `~/.jagent-harness` | Global config root for `SYSTEM.md`, `AGENTS.md`, and global file-based skills. |
| `JAGENT_CORS_ORIGIN` | `http://localhost:5173` | Console UI CORS origin. Used only by the console starter. |
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
