# 随 Skill 按需加载 MCP 工具

[English](skills-and-mcp.md) | **简体中文**

[文档导航](../README.zh-CN.md) · [配置与 Skills（英文）](configuration.md)

将业务流程写在 Skill 中，将业务接口通过 MCP 提供。模型读取某个业务 Skill 后，它需要的工具才会加入后续调用，并在当前会话中持续可用。

## 1. 配置 MCP 服务

在 Console 的 MCP 页面选择服务，将 **Tool loading** 设为 **Load with skill** 并保存。也可以直接编辑 `mcp.json`：

```json
{
  "mcpServers": {
    "merchant": {
      "transport": "streamable-http",
      "url": "https://merchant.example.com/mcp",
      "exposure": "on_skill",
      "headers": {
        "Authorization": "Bearer ${MERCHANT_MCP_TOKEN}"
      }
    }
  }
}
```

替换为实际服务地址和鉴权信息。示例中的 `MERCHANT_MCP_TOKEN` 需要在服务端环境变量中设置；无需鉴权时可省略 `headers`。

项目会话使用项目或项目默认 MCP 配置；个人 Agent 使用 Agent 默认 MCP 配置。

| 配置 | 作用 |
| --- | --- |
| `exposure: "on_skill"` | 加载 Skill 后，提供它声明的工具。 |
| `exposure: "eager"` | 始终提供已启用的工具；省略 `exposure` 时使用此模式。 |
| `enabled: false` | 禁用整个服务。 |
| `enabledTools` | 限制允许使用的远端工具名，例如 `["get_order", "create_refund"]`。省略表示全部允许，空数组表示全部禁用。 |

## 2. 编写业务 Skill

通过 Console 导入 Skill ZIP，或通过知识文件 API 保存 `skills/order-refund/SKILL.md`：

```markdown
---
name: order-refund
description: 查询订单、判断退款资格并办理退款。
tool_dependencies:
  - server: merchant
    tools:
      - get_order
      - check_refund_eligibility
      - create_refund
---

# 订单退款

1. 获取订单号，调用 get_order 核对订单状态和支付金额。
2. 调用 check_refund_eligibility 判断退款资格。
3. 满足条件并确认退款金额后，调用 create_refund。
4. 向用户说明处理结果；后续追问可继续查询订单状态。

调用工具时，使用 skill 返回的 activatedTools 中的 modelName，
并按照工具参数定义填写参数。
```

`server` 对应 `mcpServers` 中的服务名；`tools` 填写该 MCP 服务的原始工具名。可以声明多个服务，每个工具都必须存在且已启用，不支持通配符。未声明 `tool_dependencies` 的 Skill 可照常使用。

## 3. 在对话中使用

用户可以直接说：“帮我处理订单 123 的退款。”模型根据 Skill 描述选择并读取 `skills/order-refund/SKILL.md`，随后即可调用所需工具，例如 `merchant__get_order`。

用户继续追问“刚才退款成功了吗”时，这些工具仍然可用。加载另一个业务 Skill 会补充它需要的工具，无需手动结束或切换业务。

## 使用规则

- **按会话保留**：回答完成、切换话题或压缩上下文不会卸载已加载的工具。新会话独立加载，其他会话不受影响。
- **读取主文件生效**：只有读取 `skills/{name}/SKILL.md` 会加载依赖；读取参考资料不会触发。重复读取不会重复添加工具。
- **遵循当前配置**：禁用服务或从 `enabledTools` 中移除工具后，该工具不能继续调用。修改 Skill 的依赖不会自动移除本会话已加载的工具。
- **重启恢复**：使用 JDBC 存储时，同一应用和会话可恢复已加载的工具；使用内存存储时，重启后需重新加载 Skill。

## 常见问题

| 问题 | 处理方式 |
| --- | --- |
| 工具在读取 Skill 前就出现了 | 检查服务是否为 `on_skill`，以及当前会话是否已加载过相关 Skill。 |
| Skill 提示依赖不可用 | 检查配置作用域、服务连接、服务名、工具名和 `enabledTools`。任一依赖不可用时，本次不会加载任何新工具；修复后重新读取 Skill。 |
| 重启后工具未恢复 | 检查是否使用持久化存储，以及是否仍为同一应用和会话。 |

Console 的工具列表显示服务提供的工具，不代表这些工具已全部加载到当前会话。
