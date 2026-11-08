# 文档导航

[项目介绍](../README.zh-CN.md) · [Quick start](../QUICK_START.md) · [English](README.md)

你可以直接在 **Project** 中工作，也可以将目标交给持续存在的 **Agent**，由它规划、追踪并通过 Project 完成任务。两层都在服务端运行；**Runner** 在实际工作环境执行本地工具；**Console** 是连接服务端的网页界面。

## 从这里开始

1. 按 [Quick start](../QUICK_START.md) 配置模型并启动服务，支持脚本、手动命令和 IDEA。
2. 选择[直接使用 Project](guides/projects.md)，或跟随 [Agent 上手指南](guides/agents.zh-CN.md) 提出一个目标。
3. 按需了解下面的功能。飞书、MCP 和外部 Agent 接入都不是首次运行的前提。

## 使用功能

| 想做什么 | 阅读文档 | 界面入口 |
| --- | --- | --- |
| 在终端的代码和文件上开展工作 | [项目与会话](guides/projects.md) | Projects → Chat |
| 交给 Agent 规划、执行并验证任务 | [个人 Agent](guides/agents.zh-CN.md) | Agents → Chat、Tasks、Projects |
| 记住偏好、沉淀可复用经验 | [记忆与学习](guides/knowledge.md) | Agents → Knowledge |
| 配置指引、虚拟文件、Skills 和工具 | [配置与 Skills](guides/configuration.md) | Project / Agent → Configuration；System Settings |
| 随 Skill 按需加载 MCP 工具 | [Skills 与 MCP](guides/skills-and-mcp.zh-CN.md) | 项目配置或 System Settings → MCP Servers |
| 通过飞书与 Agent 交流 | [飞书连接](guides/feishu.md) | Agents → Configuration → Channels |
| 让其他 AI 助手直接调用 JAgent | [外部 Agent 接入](guides/external-agents.md) | 无需操作 UI；会话也显示在 Agents → Chat |

## 部署与扩展

| 想做什么 | 阅读文档 |
| --- | --- |
| 调整地址、令牌和启动默认值 | [Quick start 配置表](../QUICK_START.md#configuration-reference) |
| 在另一台机器运行工具、恢复中断的工作 | [Coding Runner](../examples/coding-runner/README.zh-CN.md) |
| 通过 Maven 部署服务、扩展服务端工具和数据库 | [服务端集成](../jagent-harness-sdk/README.md)、[服务端示例](../examples/server-demo/README.md) |
| 开发自定义 Runner，或将 Runner 嵌入现有 Java 应用 | [Java 客户端 SDK](../sdk/java/README.md) |
| 部署网页或开发其他形式的前端 | [Console 部署](../console/README.md) |
| 实现其他语言的客户端或 Runner | [HTTP/JSON 协议](../protocol/README.md)、[OpenAPI](../protocol/openapi.json)、[JSON Schema](../protocol/schema.json) |
| 通过代码调用个人 Agent | [外部 Agent HTTP/SDK 参考](../skills/jagent/references/api.md) |

未标注中文的指南目前为英文。[开发与文档维护](../CONTRIBUTING.md) 说明新增功能时如何更新文档；[发布说明](releasing.md) 涵盖 Maven 和发布附件。
