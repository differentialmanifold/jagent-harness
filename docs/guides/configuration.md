# Configuration and Skills

[Documentation](../README.md) · [Projects](projects.md) · [Personal Agents](agents.md)

Configure the Project or Agent where the behavior is needed. Use System Settings for defaults shared by that type of workspace. Configure models in **System Settings → Models**. Server addresses and authentication remain process settings; see [Quick start](../../QUICK_START.md#configuration-reference).

## Choose a scope

| Scope | What belongs here | Console entry |
| --- | --- | --- |
| Project defaults | Instructions, Skills and MCP servers available to projects | System Settings → Project defaults |
| One Project | That project's instructions, Skills, MCP configuration and enabled tools | Project → Configuration |
| Agent defaults | Instructions, Skills and MCP servers available to personal Agents | System Settings → Agent defaults |
| One Agent | Its instructions, tool operations, bound Runner and channels | Agent → Configuration |
| Agent knowledge | Its persistent memory, private Skills and learned changes | Agent → Knowledge |

Project and Agent defaults are separate. An Agent's private knowledge does not automatically become configuration for the Projects it creates; it passes requirements to those Projects through their conversations.

## Models and execution environments

In **System Settings → Models**, configure **Model**, **Base URL** and **API key**, matching the environment setup in Quick start. The initial model displays the values from `harness.model.*` and is editable like any other entry. Saving stores the configuration in the database; saved settings take priority on restart without rewriting environment variables. Keys are masked; leave the key blank to retain it. Save, then use **Test connection** to check connectivity.

**More options** contains provider settings, context size, accepted input types and an optional capability description. These describe the model for people configuring and selecting it; the connection test does not verify those descriptions.

The model name beside **Send** shows the current default; click it to choose another model for the next run. An Agent is not permanently bound to a model. Neither Agents nor Projects have a model-selection tool or switch models autonomously. When you explicitly ask an Agent to use a model for project work or a delegation, it can pass that exact configured name or ID as `modelId` in `projects send` or `agents send`. The server resolves unique names and rejects unknown or ambiguous ones. Omission uses the system default, independently of the originating Agent's model. Runs keep their selected configuration across Runner exchanges.

Original image attachments stay in conversation history. A model that cannot accept them receives an attachment notice; selecting a capable model exposes the original images on the next model request. This is input adaptation, not an automatic model-routing rule. Current transport supports text and images; capability descriptions do not add new media transports or tools.

**System Settings → Runners** shows each terminal's purpose, platform, declared capabilities, tools and online state. Give Runners useful descriptions when starting or embedding them. These are discovery hints; projects must still verify installed software and permissions. An Agent is bound to one Runner when created, and its projects inherit that Runner. Existing Agents on other Runners can receive delegated work. Offline jobs wait for their Runner to reconnect; directory browsing requires an online Runner.

## Instructions and virtual files

Use **Instructions** to edit a Project's or defaults scope's `AGENTS.md`. Project instructions are appended to the shared project rules; same-name project Skills and MCP servers override their defaults. An Agent's own **Configuration → General → Instructions** adds guidance for that Agent.

Instructions, Skills and supporting resources in the Console belong to the server's scoped virtual file storage. In the SQLite demo they are persisted by the server; they are not files in the Runner's workspace. Editing a Skill here does not write to a checked-out repository. The Runner's `read`, `write` and shell tools operate on its real local files.

## Understand tools and Skills

**Tools execute operations.** Server tools run on the Harness; client tools run on the selected Runner. Their descriptions and parameter schemas tell the model how to call them. Select project client tools under **Configuration → Tools**; shared built-in server tools are read-only there. Agent tools manage projects, tasks, memory, Skills and history; they do not directly expose the Runner's file and shell tools to the Agent chat.

**Skills describe reusable methods.** Their names and descriptions allow discovery; the model reads the full Skill when useful. A Skill does not install tools or grant execution permissions. Core Agent planning rules and tool schemas do not depend on loading a Skill first.

To add a Skill to a Project or a defaults scope, open its **Skills** page and import a ZIP. Supported layouts include:

```text
skills/my-skill/SKILL.md     A bundle with a skills directory
my-skill/SKILL.md            A single skill directory
SKILL.md                    A skill at the archive root
```

Include YAML `name` and `description` in `SKILL.md`. Resources keep their paths relative to that file. For a root-level archive, the declared name (or archive name if absent) determines the destination directory. For Agent-private methods, use **Knowledge → Skills & learning** or ask the Agent to save a verified method; see [Memory and learning](knowledge.md).

MCP servers can provide business tools eagerly or after a Skill declares and loads them. See [Skills and MCP](skills-and-mcp.md) for configuration, `tool_dependencies` and a complete example.

The [external Agent integration Skill](external-agents.md) serves a different installation target: it is loaded by another assistant so that assistant can call JAgent. It does not need to be imported into JAgent's own Skill library.

## Check what the model can use

Open the Project or Agent's **Configuration → Runtime Context**:

- **Runtime Preview** shows the system prompt generated by current configuration, not a past request snapshot.
- **Context** lists available context resources and Skills. Discovery does not prove a Skill has been read.
- **Tools** shows the effective tool information for the selected context. The MCP configuration catalog and a session's activated tools are different views.

Agent memory and current tasks are supplied as runtime context during its decisions. Inspect their live values in **Knowledge** and **Tasks**; do not use Runtime Preview as a record of an earlier decision's memory or plan.

## Repeated tool failures

There is no fixed limit on conversation turns. Within recent tool history, if three consecutive completed tool batches all fail with identical tools, arguments and errors, the Harness asks the model to change approach. If the next batch repeats that failure, the run ends with `NO_PROGRESS` and saves the error in the conversation. Fix the blocker or send new instructions in the same conversation to continue.

This check covers both server tools and Runner results across requests. Successful calls, changed arguments or results, and new user instructions reset the streak. It does not stop successful polling or detect every possible cycle. JDBC storage retains the full history while loading recent messages for this check and messages after the compaction cursor for model context.

## Primary Agent and Runner

In an Agent's **Configuration → General & instructions**, choose **Set as primary**. Its bound Runner becomes the default for user-created Agents and is marked **Primary Runner** in **System Settings → Runners**. The first new Agent with a Runner is selected automatically. Existing installations can choose a primary Agent explicitly. Switching the primary does not move projects or rebind any Agent. Agents creating their own colleagues continue to inherit the creator's Runner unless they explicitly select another one.

## Offline Runner packages

Set `harness.console.runner-distribution-directory` to a directory containing `coding/runner.jar` and `coding/manifest.json`. It defaults to `./data/runners`; server-demo maps it to `JAGENT_RUNNER_DISTRIBUTION_DIR`. The development launchers use the build output automatically. The [Runner guide](../../examples/coding-runner/README.md#distribute-inside-your-network) covers package preparation, authenticated downloads and SSH forwarding for machines that cannot directly reach the Harness.
