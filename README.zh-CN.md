# JAgentHarness

[English](README.md) | **简体中文**

**把目标交给 Agent，让工具在实际工作环境中执行。**

你可以直接在 Project 中工作，也可以把目标交给持续存在的 Agent，由它规划、追踪并推进任务。JAgentHarness 在服务端运行这两层能力，通过轻量 Runner 在文件和应用所在的终端执行原生工具。

## 整体架构

![Agent 负责规划和任务追踪，Project 会话推进具体工作。两层的 Agent Loop 均运行在服务端，共用会话与虚拟文件存储；轻量 Runner 在目标终端执行原生工具。](assets/architecture.zh-CN.svg)

- **Agent 规划与追踪，Project 推进具体工作。** Agent 将目标拆成任务，跟踪依赖和进度，通过 Project 会话调查、实现与验证。项目结果与定时检查促使它审查证据、调整计划。用户也可以直接操作 Project。
- **Agent Loop 在云端，原生工具在终端。** Agent 与 Project 共用服务端 Harness，项目会话通过轻量 Runner 在目标环境执行工具并回传结果，执行环境无需嵌入模型 SDK 或服务端框架。

共享 Harness 提供模型调用、上下文管理和服务端工具，以统一的会话与虚拟文件存储作为支撑。

业务 Skill 可声明 MCP 工具依赖，在读取 Skill 后才向模型提供对应接口，并在同一会话内持续可用。配置与示例见 [随 Skill 按需加载 MCP 工具](docs/skill-mcp-loading.zh-CN.md)。

跟随 [Agent 上手示例](examples/personal-agents/README.zh-CN.md)，体验从提出目标到规划、执行和验证的过程。同一目标的通过/失败记录见 [Agent 长任务验证手册](docs/agent-long-task-verification.md)（英文）。

## 三个部分，一套协议

| 部分 | 职责 | 当前实现 |
| --- | --- | --- |
| **Agent Harness** | 个人 Agent 与项目会话，共享模型调用、上下文、数据库存储和服务端工具 | Java 库与[服务端示例](examples/server-demo/README.md) |
| **Runner** | 场景原生工具、执行权限及结果回传 | [客户端 SDK](sdk/java/README.md)，并提供开箱即用的 [Coding 发行包](examples/coding-runner/README.zh-CN.md) |
| **交互前端** | 通过服务端接口管理 Agent 与项目对话、任务、知识和配置 | [Web Console](console/README.md)，可部署在服务端或客户端 |

Runner 自带工具，通过主动发起的 HTTP 长轮询连接服务端，无需开放入站端口。新场景通过实现 `ToolDefinition` 扩展工具。前端无论部署在哪里，都使用服务端 API。仅使用服务端工具的项目无需 Runner。

<details>
<summary>Runner 对话执行流程</summary>

绑定 Runner 的会话通过长轮询派发整次对话任务。后续工具调用与结果通过同一会话中的普通对话请求往返，前端持续接收一条连贯的事件流。

```mermaid
sequenceDiagram
    participant UI as 前端 Console
    participant S as 服务端 Harness
    participant R as 客户端 Runner / SDK

    R->>S: 注册本地工具描述，开始长轮询
    UI->>S: 提交消息，指定已绑定 Runner 的会话
    S-->>UI: 保持 SSE 连接，推送进度
    S-->>R: 在长轮询响应中返回对话任务

    R->>S: 调用普通对话接口，提交用户消息
    Note over S: 调用模型，执行服务端工具
    S-->>R: 返回客户端工具调用，本次请求结束

    Note over R: 执行本地工具
    R->>S: 同一会话提交 role=tool 的执行结果
    Note over S: 追加历史，继续 Agent 循环
    S-->>R: 返回下一批工具调用或最终回答

    R->>S: 上报任务完成
    S-->>UI: 推送完成结果，结束 SSE
```

工具调用与结果回传可以重复多轮。执行本地工具期间，Runner 继续长轮询以接收取消指令。由应用自身发起对话时，可以直接使用 `AgentClient.run()`，无需经过 Runner 任务派发。

</details>

## 快速体验

安装 **JDK 8+**、**Maven 3.6.3+** 和 **Node.js 22.12+**，准备一个兼容 OpenAI 的模型接口。示例自带 SQLite，无需安装数据库服务。

```sh
git clone https://github.com/differentialmanifold/jagent-harness.git
cd jagent-harness
cp .env.example .env.local
```

在 `.env.local` 中配置模型：

```dotenv
JAGENT_MODEL=your-model-name
JAGENT_OPENAI_BASE_URL=https://your-provider.example/v1
JAGENT_OPENAI_API_KEY=your-api-key
```

启动三个部分：

```sh
node scripts/dev.mjs
```

打开 **http://127.0.0.1:5175**，选择一种方式开始：

- **让 Agent 自主推进：** 打开 **Agents → New Agent**，填写名称并选择 **coding-local**。在 Chat 中提出目标，它可以自行创建项目和工作目录。[简短的 Agent 示例](examples/personal-agents/README.zh-CN.md) 提供可直接发送的要求和观察指引，[长任务验证手册](docs/agent-long-task-verification.md)（英文）用来给同一次目标打分。
- **操作自己的项目：** 添加项目，选择 **coding-local**，浏览或输入该终端上的工作目录，然后发送：

> 阅读工作区中的 README，总结这个项目。

服务端推进对话，Runner 在目标机器读取文件。新项目默认启用全部七个 coding 工具；在 **Projects → Configuration → Tools → Client tools** 中修改项目工具选择，已选工具以 **Full access** 模式执行，不弹出审批。一个 Runner 可以服务多个不同目录的项目。按 **Ctrl+C** 停止示例。

独立启动、手动命令、远程终端和 Windows/macOS 快捷方式见 [Quick start](QUICK_START.md)（英文）。

## 接入项目

- [服务端集成](jagent-harness-sdk/README.md)：通过 Maven 库部署和扩展 Harness。
- [Coding Runner](examples/coding-runner/README.zh-CN.md)：在目标终端运行内置工具。
- [Web Console](console/README.md)：在服务端或客户端部署同一个前端。
- [Java 客户端 SDK](sdk/java/README.md)：通过 `AgentRunner` 托管原生工具，或通过 `AgentClient` 发起对话。
- [HTTP/JSON 协议](protocol/README.md)：实现其他语言的 Runner 或其他形式的前端。

除 Runner 指南外，上述集成文档目前为英文。本源码树的目标版本为 `2.1.0`。使用本地修改构建独立 Maven 项目前，请先运行 `mvn install`。当前服务端支持单实例部署和应用级身份验证，终端权限由 Runner 控制。

源码目录按职责划分：`jagent-harness-sdk/` 是服务端库，`sdk/java/` 是包含 Runner 运行能力的通用客户端 SDK，`examples/` 是服务端与 coding 工具宿主示例，`console/` 是前端，`protocol/` 是跨语言协议及其测试。

## 开发

```sh
mvn clean install
npm ci --prefix protocol
npm test --prefix protocol
npm ci --prefix console
npm test --prefix console
npm run build --prefix console
```

Maven 构建共享 API、Java 客户端 SDK、Coding Runner、服务端库和服务端示例。前端独立构建，产物位于 `console/dist/`。发布说明见 [Releasing](RELEASING.md)。

[MIT License](LICENSE)。
