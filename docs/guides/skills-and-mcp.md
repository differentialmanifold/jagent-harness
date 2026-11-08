# Load MCP tools with skills

**English** | [简体中文](skills-and-mcp.zh-CN.md)

[Documentation](../README.md) · [Configuration and Skills](configuration.md)

Describe a business workflow in a skill and provide its interfaces through MCP. When the model reads the skill, its required tools become available for subsequent calls and remain available in the current session.

## 1. Configure the MCP server

In the Console's MCP page, select a server, set **Tool loading** to **Load with skill**, and save. You can also edit `mcp.json` directly:

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

Replace the URL and authentication details with those for your service. Set `MERCHANT_MCP_TOKEN` in the server environment for this example, or omit `headers` if authentication is not required.

Project sessions use project or Project defaults MCP configuration. Personal Agents use Agent defaults MCP configuration.

| Setting | Behavior |
| --- | --- |
| `exposure: "on_skill"` | Makes a skill's declared tools available after the skill is loaded. |
| `exposure: "eager"` | Always provides enabled tools. This is the default when `exposure` is omitted. |
| `enabled: false` | Disables the entire server. |
| `enabledTools` | Restricts allowed remote tool names, for example `["get_order", "create_refund"]`. Omit to allow all tools; use an empty array to disable all tools. |

## 2. Write the business skill

Import a skill ZIP through the Console, or use the knowledge-file API to save `skills/order-refund/SKILL.md`:

```markdown
---
name: order-refund
description: Look up orders, check refund eligibility, and process refunds.
tool_dependencies:
  - server: merchant
    tools:
      - get_order
      - check_refund_eligibility
      - create_refund
---

# Order refunds

1. Get the order ID and call get_order to check its status and payment amount.
2. Call check_refund_eligibility to determine whether a refund is allowed.
3. Once eligible and the refund amount is confirmed, call create_refund.
4. Explain the result to the user. Use the tools again for follow-up status checks.

Call tools using the modelName values in activatedTools returned by skill,
and supply arguments according to each tool's parameter definitions.
```

`server` must match a server name in `mcpServers`. Use that MCP server's original tool names in `tools`. You can declare multiple servers. Every declared tool must exist and be enabled; wildcards are not supported. Skills without `tool_dependencies` work as usual.

## 3. Use it in a conversation

A user can ask, “Help me refund order 123.” The model selects the skill based on its description, reads `skills/order-refund/SKILL.md`, and can then call the required tools, such as `merchant__get_order`.

Those tools remain available when the user asks, “Did the refund go through?” Loading another business skill adds its required tools. Users do not need to explicitly end or switch workflows.

## Usage rules

- **Tools stay available for the session.** Completing an answer, changing topics, or compacting context does not unload them. New sessions load tools independently; other sessions are unaffected.
- **Read the main skill file to load tools.** Only reading `skills/{name}/SKILL.md` loads its dependencies. Reading reference files does not. Repeated reads do not add duplicate tools.
- **Current configuration applies.** Disabling a server or removing a tool from `enabledTools` prevents further calls to it. Editing a skill's dependencies does not automatically remove tools already loaded in the session.
- **Restart recovery depends on storage.** With JDBC storage, loaded tools can be restored for the same application and session. With memory storage, the skill must be loaded again after a restart.

## Troubleshooting

| Issue | What to check |
| --- | --- |
| Tools appear before the skill is read | Check that the server uses `on_skill` and that the current session has not already loaded a relevant skill. |
| A skill reports an unavailable dependency | Check the configuration scope, server connection, server name, tool names, and `enabledTools`. If any dependency is unavailable, no new tools are loaded. Fix the issue and read the skill again. |
| Tools are not restored after a restart | Check that you use persistent storage and are continuing the same application and session. |

The Console's tool list shows the tools a server provides. It does not indicate that all of them are loaded in the current session.
