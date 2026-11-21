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

**Image input** is visible with the main settings. **More options** contains provider settings, context size and an optional capability description. These describe the model for people configuring and selecting it; the connection test checks text connectivity, not image support.

The model beside **Send** applies to the next run. Agents and Projects do not have a permanent model. For delegated work, an explicit configured name or ID takes priority; unknown or ambiguous names are rejected. Otherwise ordinary work uses the default. For visual inspection, the Harness uses the default if it supports images, or the first enabled image-capable model in the list. An explicit text-only selection or an empty vision catalog produces an error instead of silently substituting a model. Runs keep their selected configuration across Runner exchanges.

A text-only model receives attachment references, not pixels. It can return an image or pass it to a Project session or another Agent for inspection. Original images selected for a final web reply remain in that conversation after the Runner disconnects.

**System Settings → Runners** shows each terminal's purpose, platform, declared capabilities, tools and online state. Give Runners useful descriptions when starting or embedding them. These are discovery hints; projects must still verify installed software and permissions. An Agent is bound to one Runner when created, and its projects inherit that Runner. Existing Agents on other Runners can receive delegated work. Offline jobs wait for their Runner to reconnect; directory browsing requires an online Runner.

Each user has a mainbot. The first authorized Runner binds to it; the all-in-one launcher also registers the source directory. For manual startup, select a Runner and set **JAgentHarness source directory on Runner** in mainbot’s General settings. Its built-in `jagent-help` and `runner-setup` Skills are scoped to its identity within the current user space.

## Ask mainbot to configure JAgentHarness

You can ask mainbot to make supported Console changes directly. For example:

> Add model `<provider-model-name>` as “Image reader”, using the same provider, endpoint and stored key as `<existing-model-name>`. Enable image input, keep my current default, and test the connection.

Use actual model names and declare image support only for a model that supports it. mainbot can also update an Agent's instructions, rename a conversation, change a project's selected Runner tools, or install a text Skill in project or Agent defaults.

mainbot's `harness` tool discovers operation schemas on demand and calls the same business services as Console. Available resources are:

| Resource | Supported management |
| --- | --- |
| Models | List, add, edit, remove, set default, test text connectivity |
| Agents | Profiles, tools, task viewing, conversations, private knowledge and undo |
| Projects | List/inspect sessions, create/rename/delete sessions, select Runner tools |
| Runners | List terminals and browse directories |
| Knowledge | Read/write/remove Instructions and text Skill files in project/default scopes |

Changes stay inside the current user's space and appear through the existing Console interfaces. Other Agents and project conversations do not receive `harness`; they can delegate support requests to mainbot. It is listed under mainbot's **Configuration → Tools** and can be disabled there.

Model edits preserve unrelated entries and use the same revision checks as the page. Existing keys can be reused server-side for the same provider and endpoint; they are never returned to the model. New keys should be entered in **Models**, not pasted into chat. A text connection test does not prove image support. Visual work never changes the default model.

The supported operations are explicit, not a general HTTP proxy. MCP configuration, channel authorization, device access and ZIP upload still use their documented Console workflows. Execution and delegation continue through the existing `projects` and `agents` tools. Browser-only preferences are separate from server configuration.

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

## mainbot’s Runner

**System Settings → Runners** marks the Runner bound to mainbot. Ask mainbot for another Agent when you need a separate role. It can share its own Runner or create the Agent on another registered Runner for work on a different machine.

## Offline Runner packages

Set `harness.console.runner-distribution-directory` to a directory containing `coding/runner.jar` and `coding/manifest.json`. It defaults to `./data/runners`; server-demo maps it to `JAGENT_RUNNER_DISTRIBUTION_DIR`. The development launchers use the build output automatically. The [Runner guide](../../examples/coding-runner/README.md#distribute-inside-your-network) covers package preparation, authenticated downloads and SSH forwarding for machines that cannot directly reach the Harness.
