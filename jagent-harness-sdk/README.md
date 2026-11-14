# Server integration

[Documentation](../docs/README.md) · [Server host example](../examples/server-demo/README.md)

Run the harness independently of the applications that execute its tools. This Maven integration hosts the agent loop, model connection, and conversation state; clients embed the lightweight SDK and connect over HTTP. Your server application owns deployment, configuration, and storage.

The Spring modules target Java 8 and Spring Boot 2.7.18. For a complete runnable application, see [server-demo](../examples/server-demo/README.md).

## Add Maven dependencies

Add these dependencies to a Spring Boot application:

```xml
<dependency>
  <groupId>io.github.differentialmanifold</groupId>
  <artifactId>jagent-console-spring-boot-starter</artifactId>
  <version>2.2.1</version>
</dependency>
<dependency>
  <groupId>io.github.differentialmanifold</groupId>
  <artifactId>jagent-store-jdbc</artifactId>
  <version>2.2.1</version>
</dependency>
<dependency>
  <groupId>org.xerial</groupId>
  <artifactId>sqlite-jdbc</artifactId>
  <version>3.45.3.0</version>
  <scope>runtime</scope>
</dependency>
```

For local source builds, first run `mvn install` in the framework repository. The server libraries are ordinary JARs; package your host application with Spring Boot's Maven plugin and deploy its executable JAR.

Provide a `@SpringBootApplication` entry point and application configuration:

```yaml
server:
  address: 127.0.0.1
  port: 18181
spring:
  datasource:
    url: jdbc:sqlite:agent.db
    driver-class-name: org.sqlite.JDBC
harness:
  protocol:
    token: ${JAGENT_CLIENT_TOKEN:}
  model:
    provider: openai-compatible
    model: ${JAGENT_MODEL}
    base-url: ${JAGENT_OPENAI_BASE_URL}
    api-key: ${JAGENT_OPENAI_API_KEY:}
```

Set the model environment variables before launching. Configure a shared API token when binding to a non-loopback address, and terminate HTTPS at your deployment boundary. Use persistent storage for the database. The shared console is a separate web application; configure its API proxy to point at your server.

## Execution boundary

The server owns the agent loop, model calls, and conversation history. Applications host an `AgentRunner` that registers local tools and receives work over the [protocol](../protocol/README.md). Users submit messages through `/api/v1/chat`; the server selects the project's execution target. Only the assigned Runner exchanges tool calls and results through `/api/v1/runners/chat`. No device callback endpoint is needed.

The host may also register server-side tools as `ToolDefinition` beans or use `ToolProvider`. Client tools implement the same Java contract but stay in their own application. Tool names must be unique across the combined catalog.

Skills are Markdown instructions stored through the Console or knowledge-file API. See [Configuration and Skills](../docs/guides/configuration.md) for scopes and ZIP import, and [Skills and MCP](../docs/guides/skills-and-mcp.md) for optional tool activation. Tool implementations remain in their server or Runner host.

## Select modules and extension points

| Module | Purpose |
| --- | --- |
| `jagent-core` | Agent loop, personal Agent runtime, tasks, knowledge, tools, and storage interfaces |
| `jagent-api` | Shared tool contracts and conversation protocol types for both sides |
| `jagent-spring-boot-starter` | Harness and personal Agent lifecycle and auto-configuration |
| `jagent-console-spring-boot-starter` | HTTP APIs, SSE/long-poll transport, and optional Feishu channel |
| `jagent-store-jdbc` | JDBC implementations of storage interfaces |
| `jagent-mcp-client` | MCP client support |
| `jagent-mcp-spring-boot-starter` | Optional MCP auto-configuration |

Use `SystemPromptContributor` for business instructions, `ModelProvider` for model integrations, `ToolContextFactory` for execution context, and `AgentEventListener` for events. Storage interfaces can be replaced with your own implementations. Default beans yield to supported user-provided components; `harness.enabled=false` disables the default agent auto-configuration.

Server settings use `harness.*`. Set `harness.console.enabled=false` to disable the chat and console HTTP endpoints while keeping the harness available to your host application. CORS and request limits use `harness.console.*`; the client API token uses `harness.protocol.token`.

## Personal Agent integration

Personal Agent logic lives in `jagent-core` under `core.personal`: `PersonalAgentService` manages Agents, chats and tasks; `PersonalAgentRuntime` processes background events and recovery; `PersonalAgentProjects` coordinates owned project work. Task rules, knowledge, learning and tool definitions use the same core session and storage interfaces.

Project and execution services are shared by the Console and Personal Agents:

- `core.project.ProjectService`: session creation, project ownership selection, Runner binding and history inspection.
- `core.execution.RunService`: submit, inspect, steer and stop project work, selecting the configured execution target.
- `core.execution.ConversationService`: advance the Harness with user messages or tool results in the same stored conversation.
- `core.runner.RunnerService`: registrations, claimed jobs, local tool results and execution observations. It uses Java futures and callbacks; HTTP SSE and long-poll responses are handled by the Console.

`PersonalAgentProjects` calls these services directly and adds Agent-specific task and ownership checks. `ExecutionAutoConfiguration` assembles the same services without requiring Spring MVC. The Spring starter enables Personal Agents when storage and execution services are available, unless `harness.personal.enabled=false`. A plain Java host can construct these services and manage their lifecycle. No extra Maven module is required.

Frontend messages use `/api/v1/chat` regardless of execution target. Registered Runners advance claimed jobs through `/api/v1/runners/chat`, which verifies Runner credentials and assignment. External Agents delegate through the existing Personal Agent APIs using the [JAgent skill and bundled client](../skills/jagent/SKILL.md). Feishu remains an optional channel under `spring.channel.feishu`.

### Personal Agent settings

| Property | Default | Purpose |
| --- | --- | --- |
| `harness.personal.enabled` | `true` | Enable personal Agents when storage and execution services are available |
| `harness.personal.learning.enabled` | `true` | Enable asynchronous knowledge reviews; foreground knowledge editing remains available when disabled |
| `harness.personal.learning.timeout-ms` | `30000` | Review cancellation deadline, clamped to 1–60 seconds; providers must honor `StopSignal` |
| `harness.personal.feishu.enabled` | `true` | Enable Feishu when its optional SDK is present; connection requires account configuration |

For Feishu, the demo includes `com.larksuite.oapi:oapi-sdk:2.8.7`. Other server hosts can add it or omit it for Console-only use. It is not a dependency of the lightweight client SDK. User setup is covered by the [Feishu guide](../docs/guides/feishu.md).

Run one Personal Agent worker per database application ID. User messages, project results and due checks wake it; waiting alone causes no model activity. Multi-replica hosts need a single-worker arrangement or must disable this feature. One decision runs per Agent; different Agents and project sessions can run concurrently.

## Storage

The demo uses SQLite. The JDBC library does not supply a database driver: your application chooses the dependency and provides `spring.datasource` configuration or a `DataSource` bean.

| Property | Purpose |
| --- | --- |
| `harness.store.jdbc.application-id` | Namespace for application data in a shared database |
| `harness.store.jdbc.platform` | `auto` detects SQLite, H2, or PostgreSQL; can also be set explicitly |
| `harness.store.jdbc.initialize-schema` | Set to `false` when managing schema creation yourself |
| `harness.store.jdbc.schema-locations` | List of custom initialization scripts |

To use another database, replace the SQLite driver in your application, configure the connection, and select or provide its schema. SQLite and H2 are covered by integration tests. PostgreSQL DDL is included but has not been verified against a live instance. Other databases may need DDL and SQL adaptations or custom storage implementations.

Custom `MessageRepository` implementations should preserve `AgentMessage.metadataJson` when saving and loading history.

Custom session stores must preserve `ownerAgentId` across a project's sessions and reject conflicting ownership. Custom Personal Agent stores must save knowledge edits and their revision/history atomically. The JDBC module supplies the Personal Agent tables alongside conversation and virtual file storage.

The current HTTP host serializes conversation writes within one process and uses an application-level API token. Multi-instance coordination and tenant-level authorization must be provided by the host application.
