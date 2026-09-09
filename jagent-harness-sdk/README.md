# Server integration

Run the harness independently of the applications that execute its tools. This Maven integration hosts the agent loop, model connection, and conversation state; clients embed the lightweight SDK and connect over HTTP. Your server application owns deployment, configuration, and storage.

The Spring modules target Java 8 and Spring Boot 2.7.18. For a complete runnable application, see [server-demo](../examples/server-demo/README.md).

## Add Maven dependencies

Add these dependencies to a Spring Boot application:

```xml
<dependency>
  <groupId>io.github.differentialmanifold</groupId>
  <artifactId>jagent-console-spring-boot-starter</artifactId>
  <version>1.1.0</version>
</dependency>
<dependency>
  <groupId>io.github.differentialmanifold</groupId>
  <artifactId>jagent-store-jdbc</artifactId>
  <version>1.1.0</version>
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

The server owns the agent loop, model calls, and conversation history. Client applications advertise their tools over the [protocol](../protocol/README.md); the server returns calls in HTTP responses, and the client sends execution results through the same chat endpoint. No device callback endpoint is needed.

The host may also register server-side tools as `ToolDefinition` beans or use `ToolProvider`. Client tools implement the same Java contract but stay in their own application. Tool names must be unique across the combined catalog.

Optional skills are Markdown instructions stored through the console or knowledge-file API. Import a ZIP containing `skills/{name}/SKILL.md`, a skill directory, or `SKILL.md` directly at the archive root. Supporting files keep their paths relative to `SKILL.md`. For a root-level skill, its descriptor name (or ZIP filename when unnamed) determines the destination directory. Skills guide tool use; they do not contain tool implementations.

## Select modules and extension points

| Module | Purpose |
| --- | --- |
| `jagent-core` | Agent loop, model providers, prompt and storage interfaces |
| `jagent-api` | Shared tool contracts and conversation protocol types for both sides |
| `jagent-spring-boot-starter` | Agent runtime auto-configuration |
| `jagent-console-spring-boot-starter` | Chat and console management APIs |
| `jagent-store-jdbc` | JDBC implementations of storage interfaces |
| `jagent-mcp-client` | MCP client support |
| `jagent-mcp-spring-boot-starter` | Optional MCP auto-configuration |

Use `SystemPromptContributor` for business instructions, `ModelProvider` for model integrations, `ToolContextFactory` for execution context, and `AgentEventListener` for events. Storage interfaces can be replaced with your own implementations. Default beans yield to supported user-provided components; `harness.enabled=false` disables the default agent auto-configuration.

Server settings use `harness.*`. Set `harness.console.enabled=false` to disable the chat and console HTTP endpoints while keeping the harness available to your host application. CORS and request limits use `harness.console.*`; the client API token uses `harness.protocol.token`.

## Storage

The demo uses SQLite. The JDBC library does not supply a database driver: your application chooses the dependency and provides `spring.datasource` configuration or a `DataSource` bean.

| Property | Purpose |
| --- | --- |
| `harness.store.jdbc.application-id` | Namespace for application data in a shared database |
| `harness.store.jdbc.platform` | `auto` detects SQLite, H2, or PostgreSQL; can also be set explicitly |
| `harness.store.jdbc.initialize-schema` | Set to `false` when managing schema creation yourself |
| `harness.store.jdbc.schema-locations` | List of custom initialization scripts |

To use another database, replace the SQLite driver in your application, configure the connection, and select or provide its schema. SQLite and H2 are covered by integration tests. PostgreSQL DDL is included but has not been verified against a live instance. Other databases may need DDL and SQL adaptations or custom storage implementations.

The current HTTP host serializes conversation writes within one process and uses an application-level API token. Multi-instance coordination and tenant-level authorization must be provided by the host application.
