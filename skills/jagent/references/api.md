# HTTP client reference

Use these endpoints to find or create a Personal Agent and delegate work to it. Runner discovery helps select an execution terminal during setup; you do not need to implement Runner execution. In particular, `/api/v1/runners/chat` is an authenticated, internal Runner protocol, not a way to send an Agent a goal.

## SDK

```javascript
import { JAgentClient } from '/absolute/path/to/jagent/scripts/jagent.mjs'

const client = new JAgentClient() // JAGENT_SERVER_URL and JAGENT_CLIENT_TOKEN
const agents = await client.agents()
// Reuse a suitable enabled Agent, or discover terminals and create one:
const runners = await client.runners()
// Choose the online Runner matching the requested environment, not just the first entry.
const agent = await client.createAgent({
  name: 'Developer', runnerId: 'runner_returned_by_the_server'
})
const agentId = agent.id
const chat = await client.createConversation(agentId, 'Todo CLI')
const receipt = await client.send(agentId, chat.sessionId,
  'Build a small todo CLI with add, list and complete commands. Verify persistence and provide the test results.')
const turn = await client.wait(agentId, chat.sessionId, receipt.messageId, { timeoutMs: 30000 })
const tasks = await client.tasks(agentId, chat.sessionId)
const page = await client.history(agentId, chat.sessionId, { limit: 40 })
// Continue observing or replying in this session according to the user's goal.
```

Constructor options: `server`, `token`, and per-request `timeoutMs` (default 15 seconds). `wait` accepts `timeoutMs` (default 30 seconds, maximum 5 minutes) and `intervalMs` (default 2 seconds). It waits for one submitted message to reach `DONE`, `FAILED`, or `CANCELLED`; it does not wait for the entire goal or automatically retry failed requests.

`createAgent({ name, runnerId, description, instructions })` creates an enabled Agent. `name` is required (1–128 characters); `instructions` is optional enduring guidance (up to 16,000 characters). `runnerId` must identify an existing Runner; it is required and cannot be changed after creation. The SDK sends no profile ID, so this method never updates an existing Agent. The server allocates project directories on the selected Runner when needed. Both `agents()` and `createAgent()` return summaries containing `id`, `name`, `enabled`, `instructions` and `runnerId`.

`runners()` reads `GET /api/v1/runners` and returns `runnerId`, `name`, `description`, `platform`, `capabilities`, `online`, `runtime`, `workspaces`, `workspaceRoot` and tool names/descriptions. No Runner credentials are needed for discovery; the usual server Bearer token applies. An offline Runner must reconnect before it can execute work.

`send(agentId, sessionId, text, { messageId, modelId, images })` optionally accepts a caller-chosen message ID. Otherwise it generates one before making the request. Sending the same ID with the same Agent, conversation and text returns the original receipt; reusing it for different content is a conflict.

## Endpoints

Use `Authorization: Bearer <token>` when configured. JSON request bodies use `Content-Type: application/json`.

All paths below start with `/api/v1/personal`:

| Method and path | Request | Result |
| --- | --- | --- |
| `GET /agents` | — | Agent profiles; the SDK returns summaries including the bound Runner |
| `POST /agents` | `{"name":"Developer","runnerId":"runner_…","instructions":""}` | New enabled profile including `id` and its required, fixed `runnerId`. Omit `id` to create rather than update |
| `GET /agents/{agent}/conversations` | — | Conversation IDs, titles and running flags |
| `POST /agents/{agent}/conversations` | `{"title":"Todo CLI"}` | Conversation including `sessionId` |
| `POST /agents/{agent}/conversations/{session}` | `{"text":"Goal…","messageId":"msg_…"}` | Persisted inbox receipt; processing is asynchronous |
| `GET /agents/{agent}/conversations/{session}/status` | Optional `messageId` query | Current activity counts and, when requested, that message's status, answer and error |
| `GET /agents/{agent}/conversations/{session}/history` | Optional `after` cursor and `limit` (1–100; default 40) | `messages`, `nextAfter`, `hasMore` |
| `GET /agents/{agent}/cards` | — | Agent-managed tasks; SDK `tasks` filters to the requested conversation |

`agentId` identifies a persistent Agent. `sessionId` identifies one conversation with it. `messageId` identifies an accepted input or background event, not a task or a model run. Keep these identifiers distinct.

Status fields:

- `enabled`: whether the Agent can process new work.
- `queuedMessages`, `runningMessages`: decision events waiting or running; automatic learning reviews are excluded.
- `pendingProjectRuns`: project executions still being observed by this conversation.
- `openTasks`, `completedTasks`, `cancelledTasks`: task counts scoped to the conversation.
- `nextCheckAt`: earliest scheduled check as Unix milliseconds, or `0` when none is scheduled.
- `message`: present when `messageId` was requested; contains `id`, `status`, `answer`, `error`. `DONE` applies only to that event.

History includes user, assistant and tool messages. A page contains up to `limit` messages in chronological order. `nextAfter` is the last returned message ID, or the supplied cursor when no new messages exist. Continue while `hasMore` is true. An unknown cursor is rejected instead of silently skipping content.

Tasks include `status` (`TODO`, `DOING`, `WAITING`, `DONE`, `CANCELLED`), acceptance criteria, dependencies, progress, evidence and scheduled checks. They are maintained by the Agent; use conversation messages to adjust the goal rather than editing its task records.

## Errors and delivery

Errors use HTTP status and a JSON error body. Common cases are 401 (token required/invalid), 404 (Agent, conversation or message missing), and 409 (Agent paused or conflicting message ID). Treat a failed turn's `error` as an execution outcome even when the HTTP read itself succeeds.

The SDK does not follow redirects, retry writes, install tools, or start services. A transport failure during a send preserves `messageId` on the thrown `JAgentError`; the CLI includes it in stderr JSON. Inspect the existing message/conversation before retrying. For uncertain creation requests, list Agents or conversations first: names are not unique and repeated creation can produce duplicates. A disconnected observer does not cancel accepted Agent work.

`models()` reads `/api/v1/models` and returns the default ID and enabled models' metadata without connection details. Use it to resolve an explicit user choice, not to select models autonomously. Omit `modelId` on sends to use the system default; an explicit ID applies to that run and its tracked follow-ups. `images` uses the protocol attachment format; image-only input may use an empty `text`. Duplicate message IDs must also have identical model selection and images.

`description` on an Agent is its optional public purpose (up to 4,000 characters). The internal `agents` tool can discover and create colleagues, send goals and inspect only the delegated conversations it initiated. It does not expose private memory or unrelated chats.
