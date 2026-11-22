# 文档导航

[项目介绍](../README.zh-CN.md) · [Quick start](../QUICK_START.md) · [English](README.md)

第一个 Runner 接入后，就可以和自己的 **mainbot** 对话。把目标告诉它，它可以创建 Project、跟踪长程任务，再回到原对话交付结果。

## 从这里开始

1. 按[快速启动（英文）](../QUICK_START.md)运行程序，给 mainbot 发第一个请求。
2. 跟随[个人 Agent 指南](guides/agents.zh-CN.md)，在 Projects 和 Tasks 中查看 mainbot 的工作。你也可以[自己操作 Project](guides/projects.md)。
3. 按[接入指南](guides/access.md)将其他浏览器或机器连接到同一个用户空间。

## 使用功能

| 想做什么 | 阅读文档 | 界面入口 |
| --- | --- | --- |
| 交给 Agent 规划、执行并验证任务 | [个人 Agent](guides/agents.zh-CN.md) | Agents → Chat、Tasks、Projects |
| 在终端的代码和文件上开展工作 | [项目与会话](guides/projects.md) | Projects → Chat |
| 用浏览器检索并核实原文 | [浏览器检索](guides/browser-research.zh-CN.md) | Project → Configuration → Skills |
| 记住偏好、沉淀可复用经验 | [记忆与学习](guides/knowledge.md) | Agents → Knowledge |
| 配置指引、虚拟文件、Skills 和工具 | [配置与 Skills](guides/configuration.md) | Project / Agent → Configuration；System Settings |
| 让 mainbot 配置模型、管理 Console 资源 | [mainbot 管理能力（英文）](guides/configuration.md#ask-mainbot-to-configure-jagentharness) | mainbot → Chat |
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

[个人身份、用户凭据与远程部署](guides/access.md)
