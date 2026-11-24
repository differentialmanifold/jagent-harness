# Documentation

[Project overview](../README.md) · [Quick start](../QUICK_START.md) · [简体中文](README.zh-CN.md)

Your personal **mainbot** is ready when your first Runner connects. Start in its Chat. mainbot can create Projects, track longer tasks and bring results back to the conversation.

## Start here

1. [Run JAgentHarness and send your first request](../QUICK_START.md).
2. [Follow mainbot’s work](guides/agents.md) in its Projects and Tasks, or [open a Project yourself](guides/projects.md).
3. [Connect another browser or machine](guides/access.md) to the same personal space.

## Use JAgentHarness

| I want to… | Read | Where in the Console |
| --- | --- | --- |
| Delegate work and follow a plan through verification | [Personal Agents](guides/agents.md) · [中文](guides/agents.zh-CN.md) | Agents → Chat, Tasks, Projects |
| Chat about code or files on a terminal | [Projects and conversations](guides/projects.md) | Projects → Chat |
| Search the web with a browser and verify original sources | [Browser research](guides/browser-research.md) · [中文](guides/browser-research.zh-CN.md) | Project → Configuration → Skills |
| Keep preferences and learn reusable methods | [Memory and learning](guides/knowledge.md) | Agents → Knowledge |
| Configure instructions, virtual files, Skills and tools | [Configuration and Skills](guides/configuration.md) | Project or Agent → Configuration; System Settings |
| Ask mainbot to configure models or manage Console resources | [mainbot management](guides/configuration.md#ask-mainbot-to-configure-jagentharness) | mainbot → Chat |
| Load business MCP tools when a Skill is read | [Skills and MCP](guides/skills-and-mcp.md) · [中文](guides/skills-and-mcp.zh-CN.md) | Project configuration or System Settings → MCP Servers |
| Talk to an Agent from Feishu | [Feishu connection](guides/feishu.md) | Agents → Configuration → Channels |
| Delegate from another AI assistant without the UI | [External Agent access](guides/external-agents.md) | Optional; conversations also appear in Agents → Chat |

## Deploy or extend

These references stay beside the code they describe. Start with the component you need; you do not need to read all of them.

| Goal | Reference |
| --- | --- |
| Share a Harness with separate personal spaces; issue user credentials | [Personal access](guides/access.md) |
| Change addresses, tokens or local startup defaults | [Quick start configuration](../QUICK_START.md#configuration-reference) |
| Run coding tools on another machine; inspect recovery behavior | [Coding Runner](../sdk/node/README.md) · [中文](../sdk/node/README.zh-CN.md) |
| Build a server application with Maven, add server tools, choose storage | [Server integration](../jagent-harness-sdk/README.md) and [runnable server host](../examples/server-demo/README.md) |
| Build a custom Runner or embed one in your application | [Node.js SDK](../sdk/node/README.md) |
| Host the web UI or build another frontend | [Console deployment](../console/README.md) |
| Implement a client or Runner in another language | [HTTP/JSON protocol](../protocol/README.md), [OpenAPI](../protocol/openapi.json), [JSON Schema](../protocol/schema.json) |
| Call Personal Agents from code | [External Agent HTTP/SDK reference](../skills/jagent/references/api.md) |

## Contribute

[Development and documentation conventions](../CONTRIBUTING.md) explain where new functionality and its docs belong. [Releasing](releasing.md) covers Maven publication and release assets.
