# Personal Agents

**English** | [简体中文](agents.zh-CN.md)

[Documentation](../README.md) · [Projects](projects.md)

Give your Agent a small development task and watch it create a project, run tools, and check the result.

## 1. Start and create an Agent

Follow [Quick start](../../QUICK_START.md) to start the server, Coding Runner, and Console. Open **http://127.0.0.1:5175**.

In **Agents → New Agent**, enter **Developer**, choose your online Coding Runner, and click **Create Agent**. Instructions are optional. You do not need to create a workspace or configure Feishu.

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

## Manage the Agent

- Use **…** beside a chat to rename or delete it. Deletion is blocked during an active Agent turn. It removes queued messages and detaches follow-ups; projects, memory and Skills remain. A later Feishu message creates a new chat.
- Open **Configuration → Runtime Context** to inspect the current model, available tools and discoverable Skills. Listed Skills have not necessarily been read by the model.
- **Configuration → Tools** selects this Agent's own server-side operations. **Default project Runner** controls where new projects execute; it does not give the Agent chat direct access to every Runner tool. Configure those tools in the project's **Configuration → Tools**.
- **Configuration → Runtime Context → Runtime Preview** shows the system prompt produced from current settings. It is not a saved historical model request. Current memory and task progress are visible in **Knowledge** and **Tasks**; see [Configuration](configuration.md).

**Pause Agent** prevents new decisions and stops its current turn. Already submitted project jobs and external processes need to be inspected or stopped through their Projects. Each Agent runs one decision at a time; independent project work can proceed concurrently. For writes in the same workspace, Agent-dispatched work runs in sequence; this does not lock commands started manually outside that dispatch.

The Agent has five management tools, with operations described directly in their schemas:

| Tool | Purpose |
| --- | --- |
| `projects` | Create, inspect, send, steer and stop owned project work |
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
