# JAgentHarness

[English](README.md) | **简体中文**

**云端运行 Agent Harness，本地执行工具。**

JAgentHarness 是专为服务端部署设计的 Agent Harness，将 Agent 运行时与工具执行环境分离。业务应用嵌入轻量的工具执行 SDK，服务端负责运行 Agent、调用模型和维护会话，两端通过统一协议协同工作。

![架构图：轻量客户端 SDK 在本地执行原生工具，通过 HTTP/JSON 与远程 Agent Harness 交换消息、工具调用和执行结果。](assets/architecture.svg)

所有连接均由客户端发起。服务端通过响应返回工具调用，设备无需开放回调接口。

## 为什么要分离 Harness 与工具执行？

机器人控制程序、手机应用和桌面软件各有自己的运行环境、依赖和原生 API。工具需要在这些环境中执行，但嵌入完整的 Agent Harness 往往会引入不兼容的语言、框架或依赖。为每一种环境重新实现 Agent 循环，又意味着多维护一套系统。

JAgentHarness 用协议连接两端：**设备实现自身能力，服务端运行 Agent。** 模型接入、上下文管理和会话状态由服务端负责；工具实现、权限和执行由本地应用掌控。Agent 能力可以独立演进，无需反复改造设备端集成。

## 适用场景

| 应用 | 留在本地的能力 |
| --- | --- |
| 编程与桌面自动化 | 工作区访问、应用控制、Shell 命令 |
| 机器人控制 | 硬件驱动、传感器访问、设备动作 |
| 手机自动化 | 原生应用集成、界面操作、平台权限 |
| 现有业务系统 | 以工具形式提供的内部服务与业务操作 |

**目前已提供：** Java 服务端、轻量 Java 客户端 SDK，以及可运行的 coding 示例。机器人和手机场景用于说明架构的适用方向，项目尚未提供对应的平台工具与适配器，也尚未提供完整的 JavaScript SDK。

## 核心特性

- **面向服务端部署：** Java Agent 集成模型接入、流式响应、上下文压缩和工具编排。
- **数据库存储：** 通过 JDBC 持久化会话、执行事件和用量，数据库由业务应用选择。
- **虚拟文件系统：** 在数据库中管理提示词、skills 和附属文件，支持全局与项目作用域。
- **轻量可嵌入 SDK：** Java 客户端仅依赖 JDK HTTP 和 Jackson，将原生工具嵌入现有应用，通过跨语言协议连接服务端。
- **灵活扩展：** 组合服务端、客户端与 MCP 工具，通过 Maven 库扩展模型、提示词和存储实现。

## 快速体验

需要 **JDK 8+**、**Maven 3.6.3+**、**Node.js 22.12+**，以及兼容 OpenAI 格式的模型接口。示例内置 SQLite，无需另外启动数据库服务。

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

启动 coding 示例：

```sh
node scripts/dev.mjs
```

脚本会构建并启动 Harness 服务端、coding 客户端和 Web 控制台。打开 **http://127.0.0.1:5175**，创建项目并指定本地工作区路径，然后输入：

> 阅读这个工作区的 README，总结一下项目。

观察工具调用：服务端推进对话，coding 客户端在你的电脑上读取文件。也可以在临时工作区中，让它创建一个文件并读回内容，通过控制台的审批功能控制本地操作。按 **Ctrl+C** 停止示例。

要体验跨机器运行，将 `JAGENT_SERVER_URL` 和 `JAGENT_CLIENT_TOKEN` 指向已启动的服务端，再运行 `node scripts/dev.mjs --client-only`。工作区始终保留在客户端机器上。

独立组件启动、Windows/macOS 快捷方式、手动启动命令和排错指引见 [Quick start](QUICK_START.md)（英文）。

## 接入项目

- [Java 客户端 SDK](sdk/java/README.md)：嵌入工具执行能力，注册本地工具。
- [HTTP/JSON 协议](protocol/README.md)：为其他语言和运行环境实现客户端。
- [服务端集成](jagent-harness-sdk/README.md)：使用 Maven 库部署和配置 Harness。
- [Coding 客户端](examples/coding-java/README.md)与[服务端示例](examples/server-demo/README.md)：了解示例中的两个应用。

上述集成文档目前为英文。本源码树的目标版本为 `1.1.0`。本地开发时，先运行 `mvn install`，再构建独立的 Maven 使用方项目。当前服务端面向单实例部署，使用应用级身份验证；设备平台的权限控制由客户端应用负责。

## 开发

```sh
mvn clean install
npm ci --prefix protocol
npm test --prefix protocol
npm ci --prefix console
npm test --prefix console
npm run build --prefix console
```

Maven 运行 Java 测试，npm 运行协议与控制台测试。Maven 库的发布流程见 [Releasing](RELEASING.md)（英文）。

[MIT 许可证](LICENSE)。
