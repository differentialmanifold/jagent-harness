# Coding Runner

[English](README.md) | **简体中文**

一个运行在工作区所在机器上的无界面 Java 进程，自带 `read`、`ls`、`find`、`grep`、`write`、`edit` 和 `bash` 工具。需要 Java 8+，不包含 Spring、数据库驱动、模型 SDK 或 Web 服务。

[Harness 服务端](../../examples/server-demo/README.md)负责 Agent 循环、模型、状态和 skills 等服务端工具。[交互前端](../../console/README.md)直接访问服务端。Runner 声明内置工具，通过主动发起的 HTTP 长轮询领取任务，在本地执行并回传结果。工具实现随 Runner 一起交付，无需从 Harness 下载。

这是 [Java 客户端 SDK](../../sdk/java/README.md) 与 coding 工具打包后的可运行版本，无需额外安装 SDK。命令行入口只负责 coding 配置，注册、领取任务、取消和恢复都由 SDK 提供。

## 连接一台机器

本地开发时，直接在 IDE 运行 `RunnerMain.main()`，或不带参数运行 JAR，即可启动。默认连接 `http://127.0.0.1:18181`，可通过 `JAGENT_SERVER_URL` 或 `--server` 修改。`--help` 用于查看帮助；`recover` 只用于恢复中断任务，不是启动步骤。

在仓库根目录构建，或使用包含 Coding Runner 的发布版本：

```sh
mvn -pl examples/coding-runner -am package
```

将 `examples/coding-runner/target/jagent-coding-runner-1.2.0.jar` 复制到目标机器。在环境变量中将 `JAGENT_CLIENT_TOKEN` 设置为服务端 API 令牌，然后执行：

```sh
java -jar jagent-coding-runner-1.2.0.jar --server https://agent.example.com --name linux-dev
```

打开 Web 前端，添加项目并选择 **linux-dev**，浏览或输入该终端上的工作目录。一个 Runner 代表一台执行终端，可以服务多个工作目录不同的项目。

新项目默认启用全部七个工具。在 **Configuration → Built-in tools → Client tools** 中修改项目工具选择，下一次任务生效。已选工具使用 **Full access** 模式，不弹出审批；项目也可以关闭全部客户端工具。

工具使用所属操作系统账户的文件和进程权限运行；工作区是工作目录，不是操作系统沙箱。需要隔离时，请使用权限适当的账户或容器。Bash 工具需要本地 Shell，Windows 上需要 Git Bash。搜索优先使用 ripgrep，不可用时使用 Java 实现，也可通过 `JAGENT_RG_PATH` 指定 ripgrep 路径。Runner 启动时会打印检测到的搜索引擎，**Configuration → Built-in tools → Client tools** 中的 `find` 和 `grep` 旁会显示这份启动快照。每次搜索结果记录实际使用的引擎，包括运行中的回退。

Runner 所在机器不需要模型密钥，也不监听端口。除本机回环地址的开发环境外，请使用 HTTPS。可以通过进程管理工具让 `serve` 持续运行；按 Ctrl+C 停止进程。

## 本地工具与其他场景的 Runner

内置工具代码位于 [tools/coding](src/main/java/io/github/differentialmanifold/jagentharness/tools/coding)，由 [CodingTools](src/main/java/io/github/differentialmanifold/jagentharness/tools/coding/CodingTools.java) 注册。Java 工具实现 `ToolDefinition`，[Java SDK](../../sdk/java/README.md)负责工具宿主的完整运行流程。Coding 工具直接使用共享的 `ToolDefinition`、`ToolContext` 和取消信号接口。新增工具时，在 Runner 内实现并注册，然后重新构建和分发 Runner 可执行文件。更新前应完成尚未结束的任务。

开发其他 Java 场景时，只需引用 `jagent-client-java`、实现 `ToolDefinition`，然后交给 SDK 的 `AgentRunner` 运行，无需复制此命令行入口或重写注册、轮询和恢复逻辑。SDK 不要求工作目录，工作目录是 coding 工具自身的需要。其他语言也可以实现自己的工具接口和同一套 [HTTP/JSON 协议](../../protocol/README.md#remote-runners)，无需 MCP。服务端只接收工具名称、描述、参数结构和执行结果，不接收工具实现代码。Skills 等服务端能力继续作为服务端工具运行。

当前交互前端是网页版。后续桌面版或其他前端可以使用相同的服务端接口，无需本地网关，也无需加载 Runner 的工具。

## 状态、取消与恢复

默认本地状态目录为 `~/.jagent-runner`。使用 `--home <directory>` 或 `JAGENT_RUNNER_HOME` 可指定另一套身份。一个状态目录代表一台终端，只允许一个进程运行；不要复制到另一台机器共用身份。API 令牌从环境变量读取，不保存到本地。Runner 专用密钥与执行日志保存在本地状态目录中。

服务端将注册信息、会话绑定关系和任务保存到数据库。每次长轮询最多等待 25 秒。工具执行期间，接收线程仍保持工作，因此前端发起的取消操作可以停止本地进程。关闭浏览器不会取消执行。用户消息和工具结果继续使用普通对话接口。

断线后同一任务可能再次派发。Runner 在执行前记录任务接收状态，回传前保存工具结果，不会自动重放最初的提示词或已经开始执行的工具。如果进程在结果保存前终止，结果记为 `UNKNOWN`。

需要恢复中断任务中的待回传结果时，先停止 `serve`，检查会话和本地实际执行效果，再显式提交已保存的结果：

```sh
java -jar jagent-coding-runner-1.2.0.jar recover --server https://agent.example.com --session ses_example
```

恢复时使用原来的状态目录和可执行文件版本；工作目录和工具清单取自已保存的任务。缺失或未完成的结果以 `UNKNOWN` 回传；模型收到结果后可能发起新的工具调用。恢复完成后重新启动 `serve`。

当前实现支持每个 Runner 同时运行一个会话，前端实时流使用单个服务端实例。任务和历史记录在服务端重启后保留，流连接需要重新建立。目前未实现多实例实时事件分发和多租户身份验证；共享 API 令牌属于操作人员凭据。
