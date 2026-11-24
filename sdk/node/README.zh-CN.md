# Node.js Coding Runner

[English](README.md) · 简体中文

在有 Node.js 20（20.19 及以上）或 Node.js 22.12+ 的机器上运行 Project 工具。Runner 主动连接 Java Harness，注册 `read`、`ls`、`find`、`grep`、`write`、`edit`、`bash` 七个工具。Coding 项目也能发现内置浏览器 Skills。Windows 上使用 `bash` 需要安装 Git for Windows。安装了 ripgrep 时，`find` 和 `grep` 会调用它，控制台会显示当前搜索引擎。

## 安装与启动

包发布到所配置的 npm 仓库后，在执行工具的机器上运行：

```sh
npm install --global jagent
jagent runner --server https://harness.example.com --credentials /path/to/user.env
```

首次发布前或在内网中，使用 `npm install --global /path/to/jagent-0.1.0.tgz` 安装同一个命令。发布包中包含运行时依赖。从源码构建 tarball 时，在 `sdk/node` 下运行 `npm ci` 和 `npm pack`。本地默认地址可直接运行 `jagent runner`。无图形环境使用 `--no-browser`。用 `npm uninstall --global jagent` 卸载命令。

## 命令行

| 命令 | 用途 |
| --- | --- |
| `jagent version` | 显示本机安装的 Runner 版本，离线也可使用。 |
| `jagent runner` | 使用保存的连接启动 Runner |
| `jagent open --link` | 生成一次性控制台登录链接 |
| `jagent pair <code>` | 在 Runner 所在机器的终端确认浏览器配对 |
| `jagent recover --session <id>` | 停止运行中的 Runner 后，为中断的会话回传已有工具结果 |

Runner 将身份、任务接收记录和工具执行结果保存到 `~/.jagent-runner`，按服务地址和用户隔离。SQLite 或 PostgreSQL 由 Harness 管理。Runner 所在机器只需能访问本地工作目录并通过 HTTP(S) 连接 Harness。在不受信任的网络上使用 HTTPS。

`--home` 可为另一个 Runner 指定独立身份目录。`--name` 和 `--description` 用于控制台展示。前端和 Harness 地址不同时使用 `--console`。存在多份连接配置时，用 `--profile <id>` 选择注册时显示的 ID。Runner 默认在空闲且连续断联 24 小时后退出，可用 `--idle-disconnect-timeout <秒>` 调整。环境变量见[配置参考](../../QUICK_START.md#configuration-reference)。

## 接入自己的工具

通过 `AgentRunner.create` 传入服务地址、用户凭据和设备上的工具，调用 `serve()` 即可接收任务。设备工具可以直接返回 `success(value)`。需要在项目中选择本地目录时，再传入 `workspaces`。[SDK 示例](README.md#embed-your-own-tools)

在本目录运行 `npm test` 可验证工具执行与断线恢复。将 `JAGENT_TEST_SERVER_JAR` 指向 Harness JAR 的绝对路径可运行端到端测试。测试会启动临时 Harness 和模拟模型。
