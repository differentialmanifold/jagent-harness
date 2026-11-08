# Projects and conversations

[Documentation](../README.md) · [Quick start](../../QUICK_START.md)

Use a Project when you want to direct the work yourself. A Project holds its workspace, execution target and configuration; each conversation has its own history. For delegated planning and follow-through, use a [Personal Agent](agents.md).

## Create a project and run a task

1. Start the services using Quick start, then open the Console.
2. Add a project, enter a name and select an online Runner. Choose **Server tools only** if the project needs no terminal tools.
3. For a Coding Runner, browse an existing workspace or leave the directory empty to allocate a new one. The browser lists directories on the Runner machine, not the browser machine.
4. Send: “Create a hello.txt file with a greeting, then read it back.” Watch the tool calls and final answer in Chat.

New coding projects enable all registered client tools. Change the selection under **Configuration → Tools → Client tools**; it applies to future runs. Selected tools execute with the Runner OS account's permissions, without approval prompts. A workspace is a working directory, not an OS sandbox.

## Work with conversations

- Create separate conversations for separate lines of work. They share the project workspace and configuration, but not conversation history.
- Use the conversation menu to rename or delete a chat. Switch between conversations to inspect messages and tool output; independent conversations can run concurrently.
- Stop a running conversation from Chat when you need to intervene. Closing the browser does not cancel accepted work. Inspect local effects after interruption before repeating commands.
- Concurrent project chats can modify the same files. Give them independent directories when writes should not overlap; worktrees are not created automatically.

## Inspect the effective configuration

Open **Configuration → Runtime Context** for **Runtime Preview**, **Context** and **Tools**. It shows the current model, prompt, discoverable Skills and tool catalog. Runtime Preview is generated from current settings, not a captured historical request. A discoverable Skill has not necessarily been read by the model.

See [Configuration and Skills](configuration.md) for project overrides, defaults and tool selection. See the [Runner guide](../../examples/coding-runner/README.md#state-cancellation-and-recovery) for local journals and recovery.

## User projects and Agent projects

Your projects and each Agent's projects have separate lists. An Agent's **Projects** tab opens its ordinary project conversations, so you can inspect the work and participate. Agent tools are scoped to that Agent's own projects.

With the Coding Runner, automatically allocated directories live under `~/.jagent/workspaces/user/` or `~/.jagent/workspaces/agents/<agent-id>/`. Explicitly choosing the same directory still shares files. Ownership organizes work and scopes Agent actions; it does not create separate OS accounts or tenant authentication.
