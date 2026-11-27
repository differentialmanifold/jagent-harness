# Node.js Coding Runner

[English](README.md) · 简体中文

在有 Node.js 20（20.19 及以上）或 Node.js 22.12+ 的机器上运行 Project 工具。Runner 主动连接 Java Harness，注册 `read`、`ls`、`find`、`grep`、`write`、`edit`、`bash` 七个工具。Coding 项目也能发现内置浏览器 Skills。Windows 上使用 `bash` 需要安装 Git for Windows。安装了 ripgrep 时，`find` 和 `grep` 会调用它，控制台会显示当前搜索引擎。

Coding Runner 启动和重连时会校验当前 Harness 的源码，并缓存在本机身份目录下，其中包含配套的 Console 源码。npm 安装包的 `src/` 保留当前 Runner 自身的 TypeScript。mainbot 能查到这两个本地路径。Harness 升级时会选择新快照，原 Project 的目录仍可访问。Runner 通过 npm 独立升级。

## 安装与启动

包发布到所配置的 npm 仓库后，在执行工具的机器上运行：

```sh
npm install --global jagent
jagent runner --server https://harness.example.com --credentials /path/to/user.env
```

首次发布前或在内网中，使用 `npm install --global /path/to/jagent-0.2.0.tgz` 安装同一个命令。发布包中包含运行时依赖。从源码构建 tarball 时，在 `sdk/node` 下运行 `npm ci` 和 `npm pack`。本地默认地址可直接运行 `jagent runner`。Runner 默认在终端运行。用 `npm uninstall --global jagent` 卸载命令。

## 通过 SSH 连接

如果 Harness 在 SSH 主机的默认回环地址上监听，在执行工具的机器上运行：

```sh
jagent runner --ssh deploy@harness.example.com --credentials /path/to/user.env
```

如果通过跳板机访问另一台内网机器，加上跳板机能访问的 Harness 地址，例如 `--server https://harness.internal`。Runner 调用系统的 OpenSSH 客户端，SSH 服务端需要允许到该地址的 TCP 转发。首次交互连接会校验主机指纹；无人值守时需预先信任主机密钥并配置好密钥或 SSH agent。Harness 仍使用自己的用户凭据认证。要打开网页，另行运行 `jagent open --console https://console.example.com`，指定浏览器可访问的前端地址。

`--ssh` 支持 SSH 配置中的主机别名，可在别名下设置 `Port`、`IdentityFile` 和 `ProxyJump`。用 `--ssh-config <文件>` 指定其他配置文件。SDK 对应的选项为 `AgentRunner.create` 的 `ssh` 和 `sshConfig`。保持 SSH 别名、配置文件路径、Harness 地址和 home 一致即可复用 Runner 身份。用凭据文件首次注册后，`jagent runner`、`open`、`pair` 和 `recover` 均可复用保存的连接配置。

## 命令行

| 命令 | 用途 |
| --- | --- |
| `jagent version` | 显示本机安装的 Runner 版本，离线也可使用。 |
| `jagent runner` | 使用保存的连接启动 Runner |
| `jagent open --link` | 生成一次性控制台登录链接 |
| `jagent pair <code>` | 在 Runner 所在机器的终端确认浏览器配对 |
| `jagent recover --session <id>` | 停止运行中的 Runner 后，为中断的会话回传已有工具结果 |

Runner 将身份、任务接收记录和工具执行结果保存到 `~/.jagent-runner`，按服务地址和用户隔离。SQLite 或 PostgreSQL 由 Harness 管理。Runner 所在机器只需能访问本地工作目录并通过 HTTP(S) 连接 Harness。在不受信任的网络上使用 HTTPS。

`--home` 可为另一个 Runner 指定独立身份目录。`--name` 和 `--description` 用于控制台展示。前端和 Harness 地址不同时使用 `--console`。存在多份连接配置时，用 `--profile <id>` 选择注册时显示的 ID。注册后网络中断或电脑唤醒时，Runner 使用原身份重连，退避最长 60 秒。Runner 默认在空闲且连续断联 24 小时后退出，可用 `--idle-disconnect-timeout <秒>` 调整。环境变量见[配置参考](../../QUICK_START.md#configuration-reference)。

## 接入自己的工具

通过 `AgentRunner.create` 传入服务地址、用户凭据和设备上的工具，调用 `serve()` 即可接收任务。设备工具可以直接返回 `success(value)`。需要在项目中选择本地目录时，再传入 `workspaces`。[SDK 示例](README.md#embed-your-own-tools)

在本目录运行 `npm test` 可验证工具执行与断线恢复。将 `JAGENT_TEST_SERVER_JAR` 指向 Harness JAR 的绝对路径可运行端到端测试。测试会启动临时 Harness 和模拟模型。
