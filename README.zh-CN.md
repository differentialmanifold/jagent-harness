# JAgentHarness

[English](README.md) | **简体中文**

**服务端运行 Agent，工具在实际工作环境中执行。**

JAgentHarness 将 Agent Harness、工具 Runner 和交互前端分离。服务端负责模型与会话；轻量 Runner 自带原生工具，在目标终端执行；前端通过服务端接口进行交互，既可以部署在服务端，也可以运行在客户端。

![三个独立部分：交互前端和 coding Runner 分别连接服务端 Agent Harness。](assets/architecture.svg)

## 三个部分，一套协议

| 部分 | 职责 | 当前实现 |
| --- | --- | --- |
| **Agent Harness** | 模型调用、Agent 循环、上下文、数据库存储，以及 skills、虚拟文件等服务端工具 | Java 库与[服务端示例](examples/server-demo/README.md) |
| **Runner** | 场景原生工具、执行权限及结果回传 | [客户端 SDK](sdk/java/README.md)，并提供开箱即用的 [Coding 发行包](examples/coding-runner/README.zh-CN.md) |
| **交互前端** | 通过服务端接口管理对话、进度、项目和配置 | [Web Console](console/README.md)，可部署在服务端或客户端 |

客户端工具的实现随 Runner 一起交付。Runner 向服务端声明工具结构，通过主动发起的 HTTP 长轮询领取任务，再通过对话接口回传结果。前端无论部署在哪里，都连接服务端。Runner 无需开放入站端口。Java SDK 已包含 Runner 运行能力：新场景只需实现并注册自己的 `ToolDefinition`，不必重新开发任务接收与结果回传。只有文件类工具需要工作目录。

### 对话执行流程

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

## 为什么分离 Harness 与工具执行？

机器人控制器、手机应用、桌面应用和 Linux 工作区，各自有不同的运行环境与原生 API。工具需要在对应环境中执行，模型接入、上下文管理和持久化会话则可以统一放在服务端。

用适合设备的语言实现工具，通过简洁的 HTTP/JSON 协议复用服务端 Agent。网页版、桌面版或其他交互界面可以使用同一套服务端接口，独立于本地工具执行。

**目前已提供：** Java 服务端、轻量 Java 客户端 SDK、内置工具的 Coding Runner，以及 Web Console。其他语言可以直接实现原生 Runner，无需 MCP。项目尚未提供机器人、手机、桌面自动化适配器和 JavaScript SDK。

## 核心能力

- **面向服务端部署：** 模型接入、流式输出、上下文压缩和工具编排。
- **数据库存储：** 通过 JDBC 保存会话、事件、用量和 Runner 任务。
- **虚拟文件与 skills：** 服务端管理提示词、技能和资源文件，支持全局与项目作用域。
- **轻量本地执行：** 客户端进程运行原生工具，无需 Spring、JDBC 或模型 SDK。
- **独立交互界面：** 当前 Web Console 与未来桌面端等前端共用服务端 API。

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

打开 **http://127.0.0.1:5175**，添加项目，选择 **coding-local**，浏览或输入该终端上的工作目录，然后发送：

> 阅读工作区中的 README，总结这个项目。

服务端推进对话，Runner 在目标机器读取文件。新项目默认启用全部七个 coding 工具；在 **Configuration → Built-in tools → Client tools** 中修改项目工具选择，已选工具以 **Full access** 模式执行，不弹出审批。一个 Runner 可以服务多个不同目录的项目。按 **Ctrl+C** 停止示例。

独立启动、手动命令、远程终端和 Windows/macOS 快捷方式见 [Quick start](QUICK_START.md)（英文）。

## 接入项目

- [服务端集成](jagent-harness-sdk/README.md)：通过 Maven 库部署和扩展 Harness。
- [Coding Runner](examples/coding-runner/README.zh-CN.md)：在目标终端运行内置工具。
- [Web Console](console/README.md)：在服务端或客户端部署同一个前端。
- [Java 客户端 SDK](sdk/java/README.md)：通过 `AgentRunner` 托管原生工具，或通过 `AgentClient` 发起对话。
- [HTTP/JSON 协议](protocol/README.md)：实现其他语言的 Runner 或其他形式的前端。

除 Runner 指南外，上述集成文档目前为英文。本源码树的目标版本为 `1.2.0`。使用本地修改构建独立 Maven 项目前，请先运行 `mvn install`。当前服务端支持单实例部署和应用级身份验证，终端权限由 Runner 控制。

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
