# Documentation

[Project overview](../README.md) · [Quick start](../QUICK_START.md) · [简体中文](README.zh-CN.md)

JAgentHarness lets you work directly in a **Project**, or delegate a goal to a persistent **Agent** that plans and follows through using Projects. Both run on the server. A **Runner** executes native tools on the machine where the work happens; the **Console** is the web interface to that server.

## Start here

1. [Run the local demo](../QUICK_START.md): model configuration, launchers, manual startup and IDEA.
2. Choose [work in a Project](guides/projects.md) or [give an Agent a goal](guides/agents.md).
3. Use the guides below as you need more capabilities. Feishu, MCP and external Agent access are optional.

## Use JAgentHarness

| I want to… | Read | Where in the Console |
| --- | --- | --- |
| Chat about code or files on a terminal | [Projects and conversations](guides/projects.md) | Projects → Chat |
| Delegate work and follow a plan through verification | [Personal Agents](guides/agents.md) · [中文](guides/agents.zh-CN.md) | Agents → Chat, Tasks, Projects |
| Keep preferences and learn reusable methods | [Memory and learning](guides/knowledge.md) | Agents → Knowledge |
| Configure instructions, virtual files, Skills and tools | [Configuration and Skills](guides/configuration.md) | Project or Agent → Configuration; System Settings |
| Load business MCP tools when a Skill is read | [Skills and MCP](guides/skills-and-mcp.md) · [中文](guides/skills-and-mcp.zh-CN.md) | Project configuration or System Settings → MCP Servers |
| Talk to an Agent from Feishu | [Feishu connection](guides/feishu.md) | Agents → Configuration → Channels |
| Delegate from another AI assistant without the UI | [External Agent access](guides/external-agents.md) | Optional; conversations also appear in Agents → Chat |

## Deploy or extend

These references stay beside the code they describe. Start with the component you need; you do not need to read all of them.

| Goal | Reference |
| --- | --- |
| Change addresses, tokens or local startup defaults | [Quick start configuration](../QUICK_START.md#configuration-reference) |
| Run coding tools on another machine; inspect recovery behavior | [Coding Runner](../examples/coding-runner/README.md) · [中文](../examples/coding-runner/README.zh-CN.md) |
| Build a server application with Maven, add server tools, choose storage | [Server integration](../jagent-harness-sdk/README.md) and [runnable server host](../examples/server-demo/README.md) |
| Build a custom Runner or embed a Runner in an existing Java application | [Java client SDK](../sdk/java/README.md) |
| Host the web UI or build another frontend | [Console deployment](../console/README.md) |
| Implement a client or Runner in another language | [HTTP/JSON protocol](../protocol/README.md), [OpenAPI](../protocol/openapi.json), [JSON Schema](../protocol/schema.json) |
| Call Personal Agents from code | [External Agent HTTP/SDK reference](../skills/jagent/references/api.md) |

## Contribute

[Development and documentation conventions](../CONTRIBUTING.md) explain where new functionality and its docs belong. [Releasing](releasing.md) covers Maven publication and release assets.
