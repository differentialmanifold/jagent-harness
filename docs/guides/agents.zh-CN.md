# 个人 Agent

[English](agents.md) | **简体中文**

[文档导航](../README.zh-CN.md) · [项目指南（英文）](projects.md)

交给 Agent 一个小开发任务，看看它如何自己创建项目、调用工具并验证结果。

## 1. 启动并创建 Agent

按 [Quick start](../../QUICK_START.md) 启动服务端、Coding Runner 和 Console，打开 **http://127.0.0.1:5175**。

进入 **Agents → New Agent**，名称填 **Developer**，选择已在线的 Coding Runner，点击 **Create Agent**。Instructions 可以留空，不需要准备工作目录，也不需要先配置飞书。

第一个绑定 Runner 的新 Agent 会自动成为 **主 Agent（Primary Agent）**。Console 默认进入它的聊天；用户创建其他 Agent 时默认使用它绑定的 Runner。已有安装或需要更换入口时，在对应 Agent 的 **Configuration → General & instructions → Set as primary** 中设置。切换主 Agent 不会迁移已有项目或 Runner 绑定。

日常直接和主 Agent 聊天即可。它可以通过自己的 Project 执行，也可以发现并委托其他 Agent，跟踪任务、检查结果后回到这里汇报。其他 Agent 仍可通过下拉框访问。Agent 自主创建助手时仍默认使用自己的 Runner，需要其他环境时显式指定 Runner ID。

## 2. 发一条消息

在这个 Agent 的 **Chat** 中发送：

> 用 Java 做一个最简单的 TODO 程序：添加任务、列出任务、完成任务。任务保存在内存里，不需要界面和第三方依赖。写一个小测试验证这三个操作，运行测试，修复失败，最后告诉我文件在哪里、测试是否通过。

Runner 环境需要能运行 `java` 和 `javac`，与 Quick start 的要求相同。

Agent 会自己安排工作。打开它的 **Projects**，可以看到创建的项目和工具执行过程；回到 Agent 的 **Chat** 查看结果。“已开始”只是进度消息，项目完成后还会继续回复结果。

每个新项目会在 Runner 上获得独立目录：

```text
~/.jagent/workspaces/
├── user/project_…/                 用户自己的项目
└── agents/agent_…/project_…/        各 Agent 自己的项目
```

目录在第一次执行时创建。仍然可以显式选择已有项目目录。这里隔离的是项目列表和默认目录，不是操作系统权限。

## 3. 再提一个小需求

在同一个对话里发送：

> 再增加删除任务的功能，更新并运行测试。

Agent 可以继续使用原来的项目完成修改并检查结果。你也可以进入项目 Chat，查看它实际执行的命令和输出。

## 4. 观察它从计划推进到验证完成

发送：

> 给这个 TODO 程序增加文件保存、加载和关键词搜索。保持没有额外依赖，原来的功能不能退化。遇到非法文件要明确报错，不能破坏当前任务。交付代码和可重复运行的测试，发现失败就继续修复。

Agent 应先通过 Project 查看现有实现，再决定怎么改。可以观察：

1. **Tasks**：左侧按总目标和具体步骤展示，支持搜索和状态筛选。选中任务，在 **Overview** 查看计划、当前进展、依赖和验收标准。
2. **Projects**：会话实际调查、实现和验证程序。独立的只读调查可以同时运行；同一目录的写入顺序进行。
3. **任务详情**：**Evidence** 展示验收说明和项目会话链接；**Executions** 保存每次执行时的要求和返回结果。检查失败时，Agent 调整方案并再次执行。
4. **Chat**：重要进展和最终验证结果。项目返回结果会自动唤醒 Agent，不需要你一直发送“继续”。

任务由 Agent 自己维护，没有手动编辑入口。需要调整计划时，在 **Chat** 中说明即可。任务和会话的数量由模型决定，不要求固定的规划、执行或验证会话。示例不一定遇到失败；如果遇到，可以查看计划和验收依据如何变化。一次执行结束，不等于任务已经验收完成。

保持服务端、Runner 和 Agent 运行，浏览器可以关闭。服务端重启后，中断的决策会触发恢复检查，先核对已有工作，再决定是否重试。本地工具中断后可能无法确认是否已经产生效果，这时需要进一步检查或你的帮助。

也可以发送“两分钟后再检查一次这个项目的文件保存测试，只在有问题时告诉我”，体验定时跟进。选中任务，在 **Overview** 中查看 **Next check**；每次只触发一次，没有新情况时静默结束。长时间运行的外部作业需要记录作业标识和日志；定时检查本身不会让 Shell 命令一直运行。

其他 Agent 的回传和 Project 后台事件会在 Chat 中显示为可展开的过程消息，并注明来源。当前 Agent 整理后的总结仍作为主要回复展示。

## 管理 Agent

- 点击会话旁的 **…** 重命名或删除。Agent 正在执行时不能删除会话；删除会清理排队消息、解除后续检查，但保留项目、记忆和 Skills。下一条飞书消息会创建新会话。
- 打开 **Configuration → Runtime Context** 查看当前模型、可用工具和可发现的 Skills。列出的 Skill 不代表模型已经读取过它。
- 打开 **Configuration → Skills** 查看内置、共享和私有 Skill，只读文件支持预览和查看原文。`runner-setup` 等内置 Skill 也会显示在 **System Settings → Agent defaults → Skills** 中。浏览 Skill 不会将它加载到会话中。
- **Configuration → Tools** 选择 Agent 自己可用的服务端操作。创建时选定的 **Bound Runner** 决定项目在哪里执行，不代表 Agent 聊天直接拥有 Runner 的全部工具；项目工具仍在项目的 **Configuration → Tools** 中选择。
- **Configuration → Runtime Context → Runtime Preview** 展示当前配置生成的系统提示词，不是某一轮历史请求的快照。实时记忆和任务进度分别在 **Knowledge** 与 **Tasks** 查看，详见[配置指南（英文）](configuration.md)。

**Pause Agent** 阻止新决策并停止当前决策。已经提交的项目任务和外部进程需要到 Project 中检查或停止。每个 Agent 同时进行一个决策，独立的项目工作可以并行；Agent 派发到同一目录的写入任务顺序执行，但不会锁住手动在该派发流程之外启动的命令。

Agent 有六个管理工具，具体操作由工具参数定义直接说明：

| 工具 | 用途 |
| --- | --- |
| `projects` | 查找或打开对应项目，检查、发送要求、调整和停止工作 |
| `agents` | 发现 Agent 和 Runner、创建 Agent、委托工作和查看回复 |
| `tasks` | 追踪计划、依赖、验收依据和一次性检查 |
| `memory` | 读取和编辑长期偏好与事实 |
| `skills` | 发现和维护自己的可复用方法 |
| `history` | 从过去的会话和项目工作中查找证据 |

核心规划规则始终存在，不需要读取某个 Skill 才能启用这些工具。Agent 通过 Project 会话调查文件、执行修改并验证结果；选择 Runner 不等于给 Agent 聊天直接开放 Shell。

## 想继续体验时

- **记忆**：说“记住，我喜欢没有额外依赖的小示例”。在 **Knowledge → Memory** 查看，后续新对话也能使用。
- **Skill**：任务成功后说“把这次已验证的实现和测试方法保存为可复用 Skill”。在 **Knowledge → Skills & learning** 查看。
- **飞书**：以后需要时，再从 Agent 的 **Configuration → Channels** 页按[连接指引](feishu.md)配置。

评审与撤销见[记忆与学习](knowledge.md)；通过其他助手提交目标见[外部 Agent 接入](external-agents.md)（均为英文）。

如果没有开始执行，检查模型是否支持工具调用、Runner 是否在线。如果命令失败，打开项目 Chat 查看错误，让 Agent 继续处理即可。

## 切换项目与执行环境

在同一个聊天中询问另一个独立仓库的绝对路径，Agent 会根据目录查找或创建对应项目。切回之前的话题时，原项目和任务仍可继续使用。同一仓库中的另一项工作通常只需要新会话。

Agent 在创建时绑定一个 Runner，创建项目时自动使用它。其他机器上的 Runner 可以使用 `--name mac-dev --description "安装了 Xcode 的 Mac 开发环境，构建前需确认签名配置"` 描述用途。然后告诉 Agent：

> 查找 Mac Runner 上合适的 Agent，没有就创建一个。把这个仓库的 URL、分支和验收要求交给它，请它验证 iOS 构建。检查它返回的构建证据后告诉我结果。

请提供真实的仓库 URL 和分支。委托通过普通会话异步执行，对方回复后会自动唤醒原 Agent。不同 Agent 的项目、任务和私有记忆保持独立；文件不会自动跨机器同步。可以切换到对应 Agent 查看其会话和项目。`agents` 工具提供查找、创建、发送和查看回复四种操作。

## 让 JAgent 准备一台 SSH 机器

直接在 JAgent 的聊天中发送：

> 把 `deploy@build.example.com` 配置成当前 Harness 的 Linux 执行环境。如果已有合适的 Runner 就复用，否则部署一个并保持运行。为它创建 Build Agent，最后告诉我验证过的 Runner ID 和 Agent ID。当前 Runner 已经配置好 SSH 登录；目标机器能访问的 Harness 地址是 `https://harness.example.com`。

需要准备执行环境时，JAgent 会通过 `skill` 工具加载内置的 `runner-setup` Skill。普通聊天只看到简短的目录说明，系统提示词不会常驻完整部署步骤，也不需要手动安装 Skill。Skill 会指引它从当前 Harness 获取安装包、检查当前 Runner 的连接地址，再选择直连或 SSH 隧道。提前准备安装包的方法见 [内网分发](../../examples/coding-runner/README.zh-CN.md#在内网分发)。

替换成实际地址。SSH 地址是要操作的机器，Harness 地址是现有的控制服务。Agent 仍然创建在当前 Harness 中，远程机器运行 Runner 来执行工具。不要把密码、私钥或 API Token 放进聊天，使用执行环境中已配置的凭据；缺少登录权限、必要软件或目标到 Harness 的网络连接时，需要先解决对应条件。

JAgent 先发现已有的 Runner 和 Agent，再通过自己当前 Runner 上的 Project 检查远程机器，按需部署 Coding Runner、按使用周期配置启动方式和日志。随后通过自己的 `agents` 工具检查注册与在线状态，用 Project 返回的部署证据核对 Runner ID，再创建或复用绑定该 Runner 的 Agent。这是 JAgent 自身的工作流程，不依赖外部助手的 Skill。启动方式见 [Coding Runner 指南](../../examples/coding-runner/README.zh-CN.md)。

对于一次性环境，可以要求整个任务验收后停止专用 Runner，保留 Agent 和项目文件以便复用。`runner-setup` 会在控制端 Project 中记录停止、重启方式及隧道归属；已有共享 Runner 保持原有生命周期。恢复时通过另一台仍在线的终端 SSH 启动，或在目标机器本地启动；已停止的 Runner 不能靠聊天自我唤醒。

| 需求或现象 | JAgent 的处理方式 |
| --- | --- |
| 同一机器上需要独立角色或记忆 | `agents create` 不传 `runnerId`，复用自己当前的 Runner |
| 新仓库或新任务 | 创建 Project 或会话，通常不需要额外 Runner |
| 不同机器、工具集或独立管理的进程 | 通过 Project 准备环境，确认 Runner 注册后再按其 ID 创建 Agent |
| Runner 状态目录被占用 | 复用已有进程；明确需要第二个 Runner 时使用独立、持久的 `--home` |
| 项目归属、并发写入或目录权限冲突 | 使用有权限的项目、等待、独立 checkout 或修正权限；不能靠换身份解决 |

自动创建的项目目录按 Agent 和 Project ID 分开。Runner 的 `--home` 隔离身份和执行记录，不隔离共享文件，也不改变操作系统权限。创建 Agent 本身不需要目录，也不会启动新进程。

## 配置模型

打开 **System Settings → Models**，常用配置只有 **Model、Base URL、API key**，与环境变量启动方式一致。首次直接展示 `harness.model.*` 的配置，和新增模型一样支持修改；保存到数据库后优先使用，不会修改环境变量。密钥不会明文返回，留空保留原密钥。保存后可测试连接。输入类型、能力说明和其他参数收在 **More options** 中；能力说明由配置者维护，连接测试不验证这些能力。

Agent 不固定绑定模型。发送按钮左侧直接显示默认模型名，点击可为下一轮选择其他模型。Agent 和 Project 都没有 `models` 工具，不自主判断或切换模型。你明确要求某个模型执行工作时，Agent 可以在 `projects send` 或 `agents send` 中传入该模型的完整名称或 ID；服务端仅匹配唯一名称，重名或不存在时要求澄清。不指定则使用系统默认模型，不继承上级 Agent 的模型。

图片原件保留在历史中，当前模型无法接收时获得附件提示，选择支持该输入的模型后再读取原件。协议目前支持文本和图片。

**System Settings → Runners** 展示终端的用途、平台、能力、工具和在线状态。离线任务等待 Runner 重连；浏览目录需要 Runner 在线。
