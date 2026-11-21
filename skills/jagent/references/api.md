# HTTP client reference

The client talks to the authenticated user's mainbot. Include any request for a specialist or a particular Runner in the goal you send to mainbot.

## SDK

```javascript
import { JAgentClient } from '/absolute/path/to/jagent/scripts/jagent.mjs'

const client = new JAgentClient() // JAGENT_SERVER_URL and JAGENT_USER_TOKEN
const chats = await client.conversations()
```

Choose the relevant returned chat. For a separate topic, obtain a new `sessionId` with `client.createConversation(title)`.

```javascript
const sessionId = 'session_id_from_the_conversation_list'
const receipt = await client.send(sessionId,
  'Build a small todo CLI with add, list and complete commands. Verify persistence and provide the test results.')
const turn = await client.wait(sessionId, receipt.messageId, { timeoutMs: 30000 })
const tasks = await client.tasks(sessionId)
const page = await client.history(sessionId, { limit: 40 })
```

Constructor options: `server`, `token`, and per-request `timeoutMs` (default 15 seconds). `wait` accepts `timeoutMs` (default 30 seconds, maximum 5 minutes) and `intervalMs` (default 2 seconds). It waits for one submitted message to reach `DONE`, `FAILED`, or `CANCELLED`; it does not wait for the entire goal or automatically retry failed requests.

`send(sessionId, text, { messageId, modelId, images })` optionally accepts a caller-chosen message ID. Otherwise it generates one before making the request. Sending the same ID with the same Agent, conversation and text returns the original receipt; reusing it for different content is a conflict.

## Endpoints

Send the User Token as `Authorization: Bearer <token>`. JSON request bodies use `Content-Type: application/json`.

All paths below start with `/api/v1/personal/agents/agent_mainbot`. The user token determines whose mainbot receives the request.

| Method and path | Request | Result |
| --- | --- | --- |
| `GET /conversations` | — | Conversation IDs, titles and running flags |
| `POST /conversations` | `{"title":"Todo CLI"}` | Conversation including `sessionId` |
| `POST /conversations/{session}` | `{"text":"Goal…","messageId":"msg_…"}` | Persisted inbox receipt; processing is asynchronous |
| `GET /conversations/{session}/status` | Optional `messageId` query | Current activity counts and, when requested, that message's status, answer and error |
| `GET /conversations/{session}/history` | Optional `after` cursor and `limit` (1–100; default 40) | `messages`, `nextAfter`, `hasMore` |
| `GET /cards` | — | Agent-managed tasks; SDK `tasks` filters to the requested conversation |

`agentId` in a receipt identifies mainbot. `sessionId` identifies one conversation with it. `messageId` identifies an accepted input or background event, not a task or a model run. Keep these identifiers distinct.

Status fields:

- `enabled`: whether the Agent can process new work.
- `queuedMessages`, `runningMessages`: decision events waiting or running; automatic learning reviews are excluded.
- `completedMessages`, `failedMessages`: completed and failed decision events, also excluding learning reviews. These count messages, not completed or failed goals.
- `latestFailure`: `null` when no decision event failed; otherwise `{ id, source, error, createdAt }` for the most recently created failed message (ties use message ID). `createdAt` is the message creation time, not the failure time. Pass its `id` as `messageId` to inspect the full receipt. Historical failures remain visible after later successful messages.
- `pendingProjectRuns`: project executions still being observed by this conversation.
- `openTasks`, `completedTasks`, `cancelledTasks`: task counts scoped to the conversation.
- `nextCheckAt`: earliest scheduled check as Unix milliseconds, or `0` when none is scheduled.
- `message`: present when `messageId` was requested; contains `id`, `status`, `answer`, `error`. `DONE` applies only to that event.

History includes user, assistant and tool messages. A page contains up to `limit` messages in chronological order. `nextAfter` is the last returned message ID, or the supplied cursor when no new messages exist. Continue while `hasMore` is true. An unknown cursor is rejected instead of silently skipping content.

Tasks include `status` (`TODO`, `DOING`, `WAITING`, `DONE`, `CANCELLED`), acceptance criteria, dependencies, progress, evidence and scheduled checks. They are maintained by the Agent; use conversation messages to adjust the goal rather than editing its task records.

## Errors and delivery

Errors use HTTP status and a JSON error body. Common cases are 401 (token required/invalid), 404 (Agent, conversation or message missing), and 409 (Agent paused or conflicting message ID). Treat a failed turn's `error` as an execution outcome even when the HTTP read itself succeeds.

The SDK does not follow redirects, retry writes, install tools, or start services. A transport failure during a send preserves `messageId` on the thrown `JAgentError`; the CLI includes it in stderr JSON. Inspect the existing message/conversation before retrying. For uncertain chat creation, list mainbot’s conversations first. Titles can repeat. A disconnected observer does not cancel accepted Agent work.

`models()` reads `/api/v1/models` and returns the default ID and enabled models' metadata without connection details. Use it to resolve an explicit user choice, not to select models autonomously. Omit `modelId` on sends to use the system default; an explicit ID applies to that run and its tracked follow-ups. `images` uses the protocol attachment format; image-only input may use an empty `text`. Duplicate message IDs must also have identical model selection and images.

Unknown or disabled explicit model IDs are rejected before enqueueing with HTTP 400 and `{ code: "MODEL_UNAVAILABLE", message }`. Retrying an already accepted message with identical input returns its existing receipt even if that model has since been disabled. Omission (or a blank model ID) leaves the choice to the default at execution start. A model disabled after acceptance but before execution causes a `FAILED` receipt with an explanatory error; a run that has already selected its model keeps that configuration.

Conversation listings include `failedMessages` alongside the existing running flag, so failed-only chats remain identifiable without reading every message. Open the chat or query its status with a message ID for details. These response additions are optional for clients talking to older servers.
