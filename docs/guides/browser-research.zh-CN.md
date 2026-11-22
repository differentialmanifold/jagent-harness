# 用浏览器检索和核实资料

[文档导航](../README.zh-CN.md) · [English](browser-research.md)

在网页或飞书里问 mainbot 一个需要查证的问题。mainbot 会交给 Project，由 Coding Runner 使用真实浏览器搜索、阅读原文，再把结论和来源链接带回原对话。检索使用 Runner 所在机器的网络，无需搜索 API Key。

## 在 Coding Project 中试用

绑定 Coding Runner 的 Project 默认启用 **bash**，自动获得 **browser-research** 和 **playwright-cli** 两个 Skill。在 **Project → Configuration → Skills** 可以查看内容，模型遇到需要浏览器的问题时才读取使用说明。

> 帮我查 SQLite 最新稳定版的版本号和发布日期，打开官网核对，附上发布说明链接。

在同一对话中追问：

> 上一版改了什么？给我能支持结论的原文。

Project 会自己选择入口、调整关键词、打开相关原文。已知官网时可以直接访问；需要全网发现时，可以选择允许自动化查询的搜索网站。要优先使用某个站点，把入口网址写在 Project 的 Instructions 里即可。回答应附上实际读过的页面，指出尚未核实的部分。

浏览器按需启动。同一工作目录里的不同 Project 会话也各自使用独立的浏览器会话。网站要求人工验证时，Project 可以改查其他来源，或说明哪些内容暂时无法核实。

## Runner 环境

Runner 所在机器需要 Node.js 20+ 和 npm，或在 Runner 的 shell 中可以直接运行的 Playwright CLI。首次使用时，Project 可以通过 npx 获取固定版本的 CLI，使用已安装的 Google Chrome，或下载 Playwright Chromium。后续调用复用本地安装。离线部署可先用 Runner 的系统账号准备好 CLI 和浏览器。

无桌面的 Linux 服务器也可以运行。若缺少浏览器系统库，可由管理员执行 `npx --yes --prefer-offline --package=@playwright/cli@0.1.25 playwright-cli install-browser --with-deps chromium`。
