# Web frontend

The frontend is independent of the Harness and runner. Host this same application on the server machine, on a client machine, or elsewhere. All API requests go to the Harness server; local tools execute in the selected runner.

## Run

From the repository root:

```sh
npm ci --prefix console
export JAGENT_SERVER_URL='https://agent.example.com'
export JAGENT_CLIENT_TOKEN='your-shared-token'
npm --prefix console run dev
```

Open `http://127.0.0.1:5175`. For a local Harness use `http://127.0.0.1:18181`. The Vite proxy forwards `/api` to `JAGENT_SERVER_URL` and adds the token on the frontend host. Model credentials belong only to the Harness. Direct npm commands do not read the repository's `.env.local`; `node scripts/start-frontend.mjs` does.

Add a project and choose an online runner, or select **Server tools only**. For a coding runner, browse or enter a directory on that terminal. New projects enable all registered client tools by default. The directory browser works through the runner’s outbound polling connection. **Projects → Configuration → Tools → Client tools** edits the project selection for future runs. Selected tools run with Full access without approval prompts.

## Build and host

```sh
npm run build --prefix console
```

Deploy `console/dist/` to a static web host. Configure that host to forward `/api/` to your Harness server, with SSE buffering disabled and a suitable long request timeout. Keep the frontend and API under the same origin. Supply the server token through the trusted host's proxy rather than putting it into JavaScript assets. The UI uses relative API paths, so no build-time server URL is embedded in the bundle.

For a local preview of the built files:

```sh
export JAGENT_SERVER_URL='https://agent.example.com'
export JAGENT_CLIENT_TOKEN='your-shared-token'
npm --prefix console run preview
```

Vite dev and preview bind to loopback by default. Use `-- --host 0.0.0.0` only when intentionally exposing them on another interface. For deployed access, use an authenticated HTTPS web host; the shared Harness API token is an operator credential.

## Other frontends

A desktop application or another interface can use the same [server APIs](../protocol/README.md). Create a session with a runner ID, submit chat to the runner dispatch endpoint, and display streamed events. Use the normal chat endpoint for sessions with server tools only. Neither frontend hosting nor its programming language changes where tools execute.

## Personal Agents

Follow the [hands-on Agent guide](../examples/personal-agents/README.md) ([简体中文](../examples/personal-agents/README.zh-CN.md)) to develop and verify a simple TODO feature, then try an improvement, scheduled follow-up, memory, and Skill reuse.

Create an Agent under **Agents → New Agent**, give it a name, and choose an online Runner. Describe a problem in its **Chat**. The Agent can create a project with its own directory, run work, inspect the result, and continue. Chat shares the project chat's Markdown rendering, live tool progress, and message composer. Use **Knowledge** for memory and reusable Skills, **Tasks** for follow-ups, and **Configuration** for instructions, Agent tools, default project Runner and channels. **Configuration → Runtime Context** for either workspace shows its model, available tools and Skills; runtime preview uses current settings, not a past request snapshot.

Agent tools are grouped by intent. Each tool exposes a small `action` selector; its schema describes the required inputs, so no Skill needs to be loaded first.

| Tool | Operations |
| --- | --- |
| `projects` | List, create, inspect, send, steer and stop owned project work |
| `tasks` | List and save goals, plans, dependencies, evidence and one-shot checks |
| `memory` | Read, add and edit persistent memory |
| `skills` | List, read, save and remove private Skills |
| `history` | Search older conversations and read nearby evidence |

New projects use the Agent's configured Runner. **Configuration → Tools** can disable an entire tool or individual operations. Skills provide reusable methods, while tool schemas define how to act.

### Projects and private tasks

Each project has one owner: the user or a personal Agent. **Chat → Projects: My projects** shows user projects. Each Agent's **Projects** tab shows only its projects; opening one switches Chat to that owner. The operator can inspect and participate in those project conversations. Agent tools enforce ownership when creating sessions in a project, inspecting or submitting work, linking tasks, and searching history.

**Tasks** is a read-only view of the Agent's working plan. Describe goals or changes in **Chat**; the Agent creates and updates tasks itself. The left list groups goals and steps, with search and status filters. Select a task to read its **Overview** (plan, progress, blockers, acceptance criteria and next check), **Evidence**, or **Executions** (requirements and project reports for each attempt). Links open the originating Agent chat or project conversation. Progress counts the lowest-level steps once; cancelled steps are shown separately from completed steps.

Dependency and parent cycles are rejected. Tracked dispatch requires acceptance criteria; completing such a task requires project evidence and completed dependencies. The Agent cancels obsolete tasks without claiming success. A due check wakes the originating personal conversation once; further checks are scheduled as needed. Short questions need no task. Users manage chats from the sidebar; the Agent follows up in the conversation where the work started.

The server wakes Agents for user messages, project results, due checks, recovery and unfinished ready work. One decision turn runs per Agent to keep plans consistent; different Agents and project sessions can run concurrently without a fixed four-worker limit. Runner jobs execute independently per session. **Pause Agent** prevents new personal turns and stops its current turn. Already submitted project jobs and external processes remain controllable through their projects. Interrupted personal turns trigger a new recovery decision that inspects persisted work instead of replaying the old turn. Task-linked dispatch persists the task update and execution intent together. Results remain separate from acceptance; obsolete requirements must be reverified.

Ownership is persisted in existing session metadata and inherited by new conversations in the same project. For older records, startup assigns ownership only when personal conversation history contains a successful new-project creation call; merely using an existing user project does not transfer ownership. Unknown legacy fields are tolerated, and old conversation and knowledge records remain stored. Legacy peer messages do not start new work.

The shared API credential remains an operator credential. Project ownership scopes Agent actions and UI organization; it does not introduce separate account authentication or isolate a shared physical filesystem. Automatic directories separate user projects from each Agent's projects; explicitly selected shared directories still share files.

Agent-dispatched work in the same workspace is exclusive unless both runs declare read-only investigation. This is a scheduling contract, not filesystem isolation, and does not lock work started manually outside Agent dispatch. Work in independent directories can run concurrently. Session-specific worktrees are not managed automatically. A running project can receive a steering message at a safe execution boundary or a stop request; external processes may still need inspection.

Each new request or background event appends a bounded runtime snapshot to conversation history: event time, persistent memory, unfinished tasks and outstanding project runs. This internal context stays out of the chat display. Completed and cancelled tasks are omitted except for dependency statuses; full tasks and project history remain available through tools. Tool turns reuse the saved snapshot and read updates from tool results, keeping the system prompt stable when tasks or memory change. Runtime Preview shows system instructions; current memory is in Knowledge and progress is in Tasks. A ready task with no outstanding execution or scheduled check receives at most one automatic continuation per revision. `WAITING` tasks need new input or a scheduled check. Long external jobs should record a job identifier, logs and outputs in their project so later sessions can inspect the real state.

### Memory, skills, and learning

Use **Agents → Knowledge → Skills & learning** to browse and edit private skills, search past conversations, and inspect learned changes. **Knowledge → Memory** contains the Agent's always-available memory. Keep preferences and stable facts there; put reusable procedures in skills, and ongoing work on private tasks.

- Memory edits preserve surrounding content and reject ambiguous replacements. The 24,000-character limit returns an error instead of truncating saved knowledge.
- Skills use `skills/<name>/SKILL.md` with YAML `name` and `description`. Reference files, scripts, templates and assets load on demand through the skill tool. Scripts do not install or grant new execution tools. Each file has a 40,000-character limit.
- Every memory edit and private skill edit keeps the previous content, reason and source conversation. Open **Learning history → View change** to compare versions, follow the evidence, or undo the latest edit to that resource. Undo is itself recorded. Concurrent edits must be reloaded before saving.
- **Search history** performs case-insensitive literal phrase matching, including Chinese substrings, over this Agent's own conversations and projects. Results link to bounded surrounding messages. Other Agents' private conversations are excluded. The JDBC implementation works without an embedding service; custom message stores can override search.
- The Agent is guided to save verified lessons after corrections and successful work. An automatic review supplements that behavior after project-result turns or turns with at least four tool executions, unless knowledge was already edited. A compaction checkpoint captures the actual messages about to leave active context. Its bounded snapshot is reviewed asynchronously; the original database history remains available.
- Each review makes one tool-free model call and can apply at most one grounded knowledge edit. It cannot operate projects, run tools, send messages, delete memory, or rewrite existing skills. Reviews yield to new foreground work, have a 30-second cancellation deadline, and skip snapshots whose knowledge revision has changed. A restart does not replay an interrupted review. A failed or empty review does not change the user's task.
- **Review this conversation** explicitly queues a review. Recent reviews show completion, no-change decisions and failures. Only actual edits appear in learning history; reviews do not add internal messages to the chat or notify Feishu.

Core planning and verification rules are always present in the system instructions; no introductory Skill is required. Use private Skills for domain methods and verified lessons. Learned skills are not published to global or project scopes automatically. Remove deletes the private SKILL.md from discovery and retains resources and change history for undo; there is no automatic age-based deletion.

### Replies and background work

Feishu messages and replies appear in the same conversation on the web. Tool calls remain visible on the web; Feishu receives the final reply. Project results and scheduled checks wake the Agent automatically. Meaningful progress, completion, failures or questions are reported in the original conversation and external channel, if any. A check with nothing worth reporting keeps its execution record without creating another reply or notification. Work started on the web stays on the web, even if that Agent also has Feishu connected.

### Connect Feishu

An Agent works before a messaging account is connected. To add mobile access:

1. Select the Agent and open **Configuration → Channels → Connect with Feishu**.
2. Scan the code or open the authorization page, then confirm the app and its permissions. Your organization may require an administrator's approval.
3. Wait for **CONNECTED**, then send a text direct message to the bot. The account that authorized the connection becomes its owner.

**Disconnect** pauses messaging and retains the bot credentials, paired account, and conversation. **Reconnect** resumes that saved connection without another QR authorization. A disconnected connection stays disconnected after a server restart. To replace the bot, expand **Use another app** and create or connect a different app.

For an existing app, expand **Use an existing app** and enter its App ID and App Secret. Enable bot capability, long-connection event delivery for `im.message.receive_v1`, and the `im:message:send_as_bot` and `im:message.p2p_msg:readonly` permissions; publish the app. Send the pairing code shown in the console to the bot to associate your account. Other senders and group chats are ignored.

No public webhook URL is required. The official Java SDK handles the outbound WebSocket connection and reconnects. Credentials are stored separately from Agent instructions and memory, never included in model input or returned by the API. Protect the server database as you would other application credentials. This integration currently accepts text and rich-text direct messages; images and files are not passed to the model. Responses and progress remain visible in the console when delivery fails; use **Retry delivery** after fixing the connection. A retry after an uncertain network outcome may deliver a message twice.
