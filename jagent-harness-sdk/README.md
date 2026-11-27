# Server integration

[Documentation](../docs/README.md) · [Harness server](../server/README.md)

These modules implement the standalone [Harness server](../server/README.md). They supply the Agent loop, model providers, persistence, server tools and HTTP endpoints. The Java build targets Java 8 and Spring Boot 2.7.18.

Build the application and its internal libraries together from the repository root:

```sh
mvn -pl server -am package
```

## Execution boundary

The server owns the agent loop, model calls, and conversation history. Applications host an `AgentRunner` that registers local tools and receives work over the [protocol](../protocol/README.md). Users submit messages through `/api/v1/chat`; the server selects the project's execution target. Only the assigned Runner exchanges tool calls and results through `/api/v1/runners/chat`. No device callback endpoint is needed.

The host may also register server-side tools as `ToolDefinition` beans or use `ToolProvider`. Runner tools implement the corresponding tool definition in their host language. Tool names must be unique across the combined catalog.

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

mainbot's `harness` tool exposes explicit user-space management operations through `HarnessOperations`. Registrations live alongside the personal tools (`ModelManagement`, `AgentManagement`, `ProjectManagement`, `KnowledgeManagement`); they call the existing domain services directly. `describe` returns operation schemas on demand, and `execute` validates arguments and the mainbot conversation identity. The model does not receive HTTP credentials or stored model keys. When adding a Console feature, keep its business rules in the service, register the corresponding management operation, and test that both entries see the same state and preserve user isolation. Never register an arbitrary HTTP dispatcher or a deployment-administrator operation. See [supported management](../docs/guides/configuration.md#ask-mainbot-to-configure-jagentharness).

Frontend messages use `/api/v1/chat` regardless of execution target. Registered Runners advance claimed jobs through `/api/v1/runners/chat`, which verifies Runner credentials and assignment. External Agents delegate through the existing Personal Agent APIs using the [JAgent skill and bundled client](../skills/jagent/SKILL.md). Feishu remains an optional channel under `spring.channel.feishu`.

### Personal Agent settings

| Property | Default | Purpose |
| --- | --- | --- |
| `harness.personal.enabled` | `true` | Enable personal Agents when storage and execution services are available |
| `harness.personal.learning.enabled` | `true` | Enable asynchronous knowledge reviews; foreground knowledge editing remains available when disabled |
| `harness.personal.learning.timeout-ms` | `30000` | Review cancellation deadline, clamped to 1–60 seconds; providers must honor `StopSignal` |
| `harness.personal.feishu.enabled` | `true` | Enable Feishu when its optional SDK is present; connection requires account configuration |

For Feishu, the server includes `com.larksuite.oapi:oapi-sdk:2.8.7`. Other server hosts can add it or omit it for Console-only use. It is not a dependency of the lightweight client SDK. User setup is covered by the [Feishu guide](../docs/guides/feishu.md).

Run one Personal Agent worker per database application ID. User messages, project results and due checks wake it; waiting alone causes no model activity. Multi-replica hosts need a single-worker arrangement or must disable this feature. One decision runs per Agent; different Agents and project sessions can run concurrently.

## Storage

The packaged Harness defaults to SQLite and also includes the PostgreSQL driver. Standalone Maven consumers supply their chosen JDBC driver and configure `spring.datasource` or a `DataSource` bean. [Packaged host database settings](../QUICK_START.md#choose-a-database)

| Property | Purpose |
| --- | --- |
| `harness.store.jdbc.application-id` | Namespace for application data in a shared database |
| `harness.store.jdbc.platform` | `auto` detects SQLite, H2, or PostgreSQL; can also be set explicitly |
| `harness.store.jdbc.initialize-schema` | Set to `false` when managing schema creation yourself |
| `harness.store.jdbc.schema-locations` | List of custom initialization scripts |

The JDBC module includes schemas for SQLite, H2 and PostgreSQL. For other databases, supply a compatible schema and verify the SQL used by the stores, or implement the storage interfaces.

Custom `MessageRepository` implementations should preserve `AgentMessage.metadataJson` when saving and loading history.

Custom session stores must preserve `ownerAgentId` across a project's sessions and reject conflicting ownership. Custom Personal Agent stores must save knowledge edits and their revision/history atomically. The JDBC module supplies the Personal Agent tables alongside conversation and virtual file storage.

The current HTTP host serializes conversation writes within one process and uses an application-level API token. Multi-instance coordination and tenant-level authorization must be provided by the host application.
