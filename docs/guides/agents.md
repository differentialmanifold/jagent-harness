# Personal Agents

**English** | [简体中文](agents.zh-CN.md)

[Documentation](../README.md) · [Projects](projects.md)

Give your Agent a small development task and watch it create a project, run tools, and check the result.

## 1. Start and create an Agent

Follow [Quick start](../../QUICK_START.md) to start the server, Coding Runner, and Console. Open **http://127.0.0.1:5175**.

In **Agents → New Agent**, enter **Developer**, choose your online Coding Runner, and click **Create Agent**. Instructions are optional. You do not need to create a workspace or configure Feishu.

The first Agent created with a Runner becomes your **primary Agent**. The Console opens it by default; its Runner is the default when you create another Agent. For an existing installation, or to choose another entry point, open that Agent's **Configuration → General & instructions → Set as primary**. Switching the primary Agent keeps all existing projects and Runner bindings unchanged.

Talk to this Agent normally. It can solve work through its own Projects, or discover and delegate to another Agent, track the task, review the result and report back here. Other Agents remain available in the selector. An Agent creating a colleague still defaults to its own Runner; an explicit Runner ID selects another environment.

## 2. Send one message

In the Agent's **Chat**, send:

> Build a tiny Java TODO program that can add a task, list tasks, and mark a task complete. Keep tasks in memory; no UI or dependencies. Run a small test covering those three operations, fix any failures, and tell me where the files are and whether the test passed.

The Runner needs `java` and `javac` on its PATH, as in the quick start.

The Agent chooses how to solve the request. Open its **Projects** tab to see the project and tool execution. Return to the Agent's **Chat** for the result. A “started” update is followed by the result once the project finishes.

Each new project gets a separate directory on the Runner:

```text
~/.jagent/workspaces/
├── user/project_…/                 Your projects
└── agents/agent_…/project_…/        Each Agent's projects
```

Directories are created on first execution. Explicitly selected project directories still work. Project ownership separates lists and defaults, not OS permissions.

## 3. Try a follow-up

Send in the same chat:

> Add the ability to delete a task. Update and run the test.

The Agent can reuse its project and check the updated result. You can also open the project chat to inspect the files, commands, and output yourself.

## 4. Follow a goal through planning, work and verification

Send:

> Extend this TODO program with saving and loading a file, plus keyword search. Keep it dependency-free. Existing behavior must keep working. Invalid files must produce a clear error without destroying the current tasks. Deliver the code and reproducible tests, and resolve any failures you find.

The Agent should inspect the existing project before deciding how to change it. Watch these places:

1. **Tasks:** browse the goal and its steps in the left list. Search or filter by status, then select a task to see its plan, current progress, dependencies and acceptance criteria in **Overview**.
2. **Projects:** sessions investigating, implementing and checking the actual program. Independent read-only investigations may run together; writes to the same directory run in sequence.
3. **Task details:** **Evidence** shows verification notes and links to project conversations. **Executions** keeps the requirements and report for each attempt. If a check fails, the Agent adjusts its approach and tries again.
4. **Chat:** meaningful progress and the verified result. Project results wake the Agent automatically; you do not need to send “continue”.

Tasks are maintained by the Agent; there is no manual task editor. To change the plan, say so in **Chat**. The model chooses how many sessions and tasks it needs. A task need not fail to demonstrate the workflow; if it does, inspect how the plan and evidence change. Execution completing is separate from the Agent accepting the task as done.

Keep the server, Runner and Agent active; the browser can be closed. After a server restart, interrupted decisions trigger a recovery check. The Agent inspects existing work before deciding what to repeat. Some interrupted local tools can have an unknown outcome and require inspection or your help.

To also try a scheduled follow-up, send “In two minutes, check this project's saved-file tests again and tell me only if there is a problem.” Select the task and look for **Next check** in **Overview**. Checks fire once and stay quiet when there is nothing to report. Long-running external jobs need their own job identifiers and logs; a scheduled check does not keep a shell command alive.

## Switch projects or execution environments

In the same chat, ask “Explain the project at `/absolute/path/to/another-repository`.” The Agent can open a separate project for that directory. Later, ask to continue the TODO program: its original project and tasks remain available. A new session within the same repository does not require another project.

For work on another machine, start a Runner there with a useful description, for example `--name mac-dev --description "Mac development machine with Xcode; verify signing configuration before building"`. In the Agent chat, ask:

> Find an Agent on the Mac Runner, or create one if needed. Ask it to check whether this repository can build for iOS. Give it the repository URL and branch, then review its build evidence and report the result.

Use a real repository URL and branch. The Agent discovers Runner capabilities, reuses or creates a colleague and sends the goal asynchronously. The colleague has its own conversations, tasks and projects; its replies wake the originating Agent. Open that colleague in **Agents** to see its work. A tracked delegation stays waiting until its result is reviewed; an acknowledgement alone is not completion. Private memory and unrelated conversations are not shared. Files and paths are not copied between machines automatically.

Use **System Settings → Models** to describe available model capabilities. Chats use the system default unless you select a model. Agents can pass a model you explicitly request when sending work; they do not choose or switch models themselves; see [Configuration](configuration.md#models-and-execution-environments).

## Ask JAgent to prepare an SSH machine

In your JAgent chat, ask:

> Prepare `deploy@build.example.com` as a Linux execution environment connected to this Harness. Reuse a suitable Runner if one already exists; otherwise deploy one and keep it running. Create a Build Agent on it and report the verified Runner and Agent IDs. SSH access is already configured on your current Runner. The Harness address reachable from the target is `https://harness.example.com`.

JAgent loads the built-in `runner-setup` Skill through its `skill` tool when this workflow is relevant. Ordinary chats only see its short catalog description; the deployment procedure is not included in every system prompt. No manual Skill installation is needed. The Skill explains how to obtain the package from this Harness, inspect the current Runner’s connection, and use a direct route or an SSH tunnel. See [offline distribution](../../examples/coding-runner/README.md#distribute-inside-your-network) to prepare the package once.

Use your actual SSH destination and reachable Harness address. These are different addresses: the first is a machine to administer, the second is the existing control service. Agents remain in this Harness; the new Runner executes tools on the SSH machine. Do not put passwords, private keys or API tokens in chat; configure access in the execution environment. If the target cannot reach the Harness or lacks required access/software, JAgent needs that prerequisite resolved.

JAgent first discovers existing Runners and Agents. It uses a Project on its current Runner to inspect the target, deploy the Coding Runner when needed, and configure startup and logs for the requested lifetime. It then checks registration and online state through its own `agents` tool, matches the Runner ID against the project's deployment evidence, and creates or reuses an Agent on that Runner. No external-assistant Skill is required. The [Coding Runner guide](../../examples/coding-runner/README.md) documents the executable and state directory.

For a one-time environment, ask it to stop the dedicated Runner after the whole task is verified and keep the Agent and project files for reuse. `runner-setup` records restart/stop instructions and any tunnel ownership in the controlling Project. Existing shared Runners keep their lifecycle. Restarting requires SSH from a still-running terminal or local startup; a stopped Runner cannot wake itself through chat.

| Need or observation | JAgent's action |
| --- | --- |
| Another persistent role or memory on the same machine | `agents create` without `runnerId` shares its current Runner |
| Another repository or task | Open a Project or conversation; no extra Runner is needed |
| Another machine, tool set or independently managed process | Use a Project to prepare that environment, verify its registered Runner, then pass its ID to `agents create` |
| Runner state directory is locked | Reuse the running process; an intentionally separate Runner needs a distinct persistent `--home` |
| Project ownership, concurrent writes or directory permissions conflict | Use the authorized project, wait, use a separate checkout, or fix access; creating identities does not resolve these conflicts |

Automatically allocated project directories are separated by Agent and Project IDs. A new Runner's `--home` separates its identity and execution records; it does not isolate shared files or change OS permissions. Agent creation itself does not require a directory or start a process.

Replies from other Agents and background project events appear as expandable process messages in Chat, with their source shown. Your Agent’s own summary remains the main reply.

## Manage the Agent

- Use **…** beside a chat to rename or delete it. Deletion is blocked during an active Agent turn. It removes queued messages and detaches follow-ups; projects, memory and Skills remain. A later Feishu message creates a new chat.
- Open **Configuration → Runtime Context** to inspect the current model, available tools and discoverable Skills. Listed Skills have not necessarily been read by the model.
- Open **Configuration → Skills** to browse built-in, shared and private Skills, with Preview and Code views for read-only files. Built-in Skills such as `runner-setup` are also listed in **System Settings → Agent defaults → Skills**. Browsing a Skill does not load it into a conversation.
- **Configuration → Tools** selects this Agent's own server-side operations. The **Bound Runner** selected at creation controls where projects execute; it does not give the Agent chat direct access to every Runner tool. Configure those tools in the project's **Configuration → Tools**.
- **Configuration → Runtime Context → Runtime Preview** shows the system prompt produced from current settings. It is not a saved historical model request. Current memory and task progress are visible in **Knowledge** and **Tasks**; see [Configuration](configuration.md).

**Pause Agent** prevents new decisions and stops its current turn. Already submitted project jobs and external processes need to be inspected or stopped through their Projects. Each Agent runs one decision at a time; independent project work can proceed concurrently. For writes in the same workspace, Agent-dispatched work runs in sequence; this does not lock commands started manually outside that dispatch.

The Agent has six management tools, with operations described directly in their schemas:

| Tool | Purpose |
| --- | --- |
| `projects` | Find or open the correct project, then inspect, send, steer and stop work |
| `agents` | Discover colleagues and Runners, create an Agent, delegate and inspect replies |
| `tasks` | Track plans, dependencies, acceptance evidence and one-shot checks |
| `memory` | Read and edit persistent preferences and facts |
| `skills` | Discover and maintain private reusable methods |
| `history` | Find evidence in older conversations and project work |

Core planning rules are always present; no Skill must be read to enable these tools. The Agent uses Project sessions to investigate files, execute changes and verify results. Its Runner selection does not give the Agent chat direct shell access.

## Explore when ready

- **Memory:** say “Remember that I prefer small examples without dependencies.” Check **Knowledge → Memory**; it is available in later chats too.
- **Skills:** after a successful task, say “Save the verified way you built and tested this example as a reusable Skill.” Inspect **Knowledge → Skills & learning**.
- **Feishu:** connect later from the Agent's **Configuration → Channels** using the [connection guide](feishu.md).

See [Memory and learning](knowledge.md) for reviews and undo, or [External Agent access](external-agents.md) to submit goals from another assistant.

If work does not start, check that the model supports tool calls and the selected Runner is online. If a command fails, open the project chat for its error and ask the Agent to resolve it.
