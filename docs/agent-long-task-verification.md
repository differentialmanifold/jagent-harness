# Agent long-task verification

Score whether Agent mode can carry one coding goal through planning, implementation, tests, and recovery. The goal is the Java TODO program in the [personal Agent guide](../examples/personal-agents/README.md), sections 2–4. The prompts below are copied from that guide. An operator with `JAGENT_*` credentials in `.env.local` runs the stages in the Console and marks the scorecard.

`node scripts/print-agent-long-task.mjs` prints the staged prompts and the scorecard. The script writes text to stdout only.

## Prerequisites

Install **JDK 8+**, **Maven 3.6.3+**, and **Node.js 22.12+** (Node 20.19 is also supported), as in [Quick start](../QUICK_START.md). `java`, `javac`, `mvn`, `node`, and `npm` must be on `PATH`. The Coding Runner compiles and runs the TODO program with `java` and `javac`.

Copy [.env.example](../.env.example) to `.env.local` and set the model connection:

```sh
cp .env.example .env.local
```

```dotenv
JAGENT_MODEL=your-model
JAGENT_OPENAI_BASE_URL=https://your-model-provider/v1
JAGENT_OPENAI_API_KEY=
```

Leave `JAGENT_CLIENT_TOKEN` empty for this local launcher run. When the server already requires a shared token, set that same `JAGENT_CLIENT_TOKEN` for the server, runner, and frontend. Keep `.env.local` on the operator machine.

## Start

From the repository root:

```sh
node scripts/dev.mjs
```

The launcher starts the harness, the Coding Runner, and the Console. Wait until the runner is online and the Console is at **http://127.0.0.1:5175**. The launcher registers the runner as **coding-local** (`JAGENT_RUNNER_NAME` in `.env.example`).

## Agent setup

1. Open **http://127.0.0.1:5175**.
2. Go to **Agents → New Agent**.
3. Name the Agent **Developer**. Instructions can stay empty.
4. Select the online runner **coding-local**.
5. Click **Create Agent**.

Leave the workspace unset. Feishu stays unset. On first execution the Agent creates a project directory under `~/.jagent/workspaces/agents/` on the runner.

## Staged prompts

Send these in the same Agent **Chat**, in order. Wait until the Agent reports that stage's result before the next message. Project results wake the Agent.

### 1. Tiny TODO

> Build a tiny Java TODO program that can add a task, list tasks, and mark a task complete. Keep tasks in memory; no UI or dependencies. Run a small test covering those three operations, fix any failures, and tell me where the files are and whether the test passed.

### 2. Delete follow-up

> Add the ability to delete a task. Update and run the test.

### 3. Save, load, and keyword search

> Extend this TODO program with saving and loading a file, plus keyword search. Keep it dependency-free. Existing behavior must keep working. Invalid files must produce a clear error without destroying the current tasks. Deliver the code and reproducible tests, and resolve any failures you find.

This is the long goal from section 4 of the personal Agent guide. The Agent should inspect the existing project before deciding how to change it. Watch the same places the guide names:

1. **Tasks:** the goal and its steps. Open a task and read its plan, progress, dependencies, and acceptance criteria in **Overview**.
2. **Projects:** sessions that investigate, implement, and check the program. Independent read-only investigations may run together; writes to the same directory run in sequence.
3. **Task details:** **Evidence** shows verification notes and links to project conversations. **Executions** keeps the requirements and report for each attempt. A failed check is followed by another attempt.
4. **Chat:** progress and the verified result.

The model chooses how many tasks and sessions to use. A finished execution and an accepted task are separate outcomes.

## Forced failure

Section 4 records a failed check and tries again. To make that visible on a program that already has a passing test, break the test on disk while stage 3 is in progress.

1. Send stage 3 and open **Projects**.
2. When a session for this project is active, open the test file in the Agent workspace (`~/.jagent/workspaces/agents/agent_…/project_…/`). Use the path reported in stage 1 or 2.
3. Insert this line as the first statement of the test method, then save. Leave the line in place for the Agent to find.

```java
throw new RuntimeException("forced verification failure");
```

4. Wait for that session to finish. Project results wake the Agent.
5. When the in-flight session finished its test run before the edit was saved, the tests can still be green. Insert the line again after the session is idle, then send:

> The tests are failing. Inspect the project, fix the failure, keep the existing TODO behavior, and re-run the tests until they pass.

A pass on this row requires a failed run caused by that line, then a later passing run whose tests still cover add, list, complete, and delete. The forced exception is removed. Dropping those checks, or leaving the exception in the file, fails the row.

## Optional scheduled check

After the stage 3 task is accepted, send the guide's scheduled follow-up:

> In two minutes, check this project's saved-file tests again and tell me only if there is a problem.

Select the task and look for **Next check** in **Overview**. The check fires once. A quiet chat is a pass when the saved-file tests are still green. The Agent reports only when there is a problem. A scheduled check does not keep a shell command alive.

## Scorecard

Mark each row after stage 3 and the forced failure. The scheduled row is optional.

| Check | Pass | Fail |
| --- | --- | --- |
| Tasks created with dependencies and acceptance | The stage 3 goal is in **Tasks**. At least one task **Overview** shows a dependency and acceptance criteria. | No task exists for the goal, or no Overview shows both a dependency and acceptance criteria. |
| Project sessions used | **Projects** lists sessions that investigated, implemented, or checked the program, and those sessions ran runner tools. | The reply stays in Agent chat with no project session and no tool run. |
| Inspect-before-edit evidence | Before the stage 3 edits, a project session read the existing TODO files. **Evidence** links to that project conversation. | Stage 3 rewrote the project with no read of the existing files, or **Evidence** has no project link. |
| Tests passing | The Agent reports the test command, the file paths, and a passing result. Re-running that command in the project directory passes. Save, load, keyword search, and a clear error on an invalid file are covered, and the invalid file leaves current tasks in place. Add, list, complete, and delete still pass. | The final test run fails, the reported command fails when re-run, the invalid-file case has no clear error, or that case drops the current tasks. |
| Recovery after a forced failure | **Executions** or **Evidence** records the forced failure, and a later attempt passes. `forced verification failure` is gone. Existing behavior stays covered. | The Agent stops on the failure, the exception remains, or the tests no longer cover the existing operations. |
| Scheduled check (optional) | **Overview** shows **Next check**. The check fires once. The chat stays quiet when the saved-file tests pass, and reports a problem only when one exists. | **Next check** is missing, the check repeats, or the Agent reports a problem while the tests pass. |

Record the model name from `JAGENT_MODEL`, the Agent name, and the pass or fail mark for each row.

## When work stalls

Confirm the model supports tool calls and **coding-local** is online. Open the project chat for a failing command and ask the Agent to resolve it. The server, runner, and Agent stay running; the browser can be closed. After a server restart, an interrupted decision triggers a recovery check: the Agent inspects existing work before repeating it.
