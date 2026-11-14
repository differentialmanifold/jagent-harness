# HTTP protocol

[Documentation](../docs/README.md) · [Java client SDK](../sdk/java/README.md)

Use this protocol to connect a custom application or language SDK to JAgentHarness. The server exposes JSON APIs and server-sent events. Java clients can use [jagent-client-java](../sdk/java/README.md).

Java tool contracts and protocol DTOs are provided by `io.github.differentialmanifold:jagent-api`. The JSON protocol remains independent of Java.

Reference materials: [OpenAPI](openapi.json), [JSON Schema](schema.json), and [fixtures](fixtures/). The API prefix is `/api/v1`. Send `Authorization: Bearer <token>` when authentication is configured.

The JSON files in `fixtures/` are protocol test samples: user messages, tool results, responses, stream events, runner registration, and polling. `schema.test.mjs` checks them against the JSON Schema during CI and release validation. They are not runtime configuration or installed tool implementations. Run `npm ci --prefix protocol` and `npm test --prefix protocol` from the repository root to validate them.

## Public chat and internal Runner chat

Frontends send messages to **`POST /api/v1/chat`** for every project. The session's project configuration selects execution; callers do not choose an endpoint based on Runner presence.

| Endpoint | Caller | Behavior |
| --- | --- | --- |
| `/api/v1/chat` | Frontend or application | Dispatch to the project's Runner when bound; otherwise execute directly. JSON waits for the result; SSE streams progress. |
| `/api/v1/runners/chat` | Registered Runner holding a claimed job | Advance the model conversation and return tool calls or an answer. Requires Runner credentials and job ownership; never enqueues another job. |

Both use the same `sessionId` and stored conversation. A Runner-bound public call accepts a user message; only the authenticated Runner can return its tool results, through the internal endpoint. The server freezes the project's tool catalog for that job. A stream disconnect does not cancel the job, and multiple observers can watch the same run. Inspect saved state/history after a disconnect rather than replaying the message.

This document and its OpenAPI cover the application/Runner protocol. To delegate goals to a Personal Agent instead, use the [JAgent skill and client](../skills/jagent/SKILL.md); those Agent APIs have a [separate reference](../skills/jagent/references/api.md).

## Submit a user message

Create a session with `POST /api/v1/sessions`. Choose the execution target when creating its project:

```json
{"title":"My project","runnerId":"runner_linux"}
```

Omit `runnerId` for a server-tools-only project. Use the returned `sessionId` with public `POST /api/v1/chat`:

```json
{
  "sessionId": "ses_example",
  "messages": [{"role":"user","content":"Read README.md"}]
}
```

The public request contains only `sessionId`, `messages`, optional `runId`, `modelId`, and `stream`. Exactly one `role=user` message is accepted. Do not send `clientTools`, `clientInstructions`, or `role=tool` results: these are rejected for both Runner-bound and server-only projects. The frontend does not implement a tool-execution loop.

The server obtains native tools and instructions from the registered Runner and the project's enabled tool selection. With no Runner, the Harness uses server tools only. A separate frontend can submit messages without embedding any tool SDK.

## Runner tool exchange

Only an authenticated Runner holding a claimed job advances local tool execution. See [Remote runners](#remote-runners) for registration and polling. Every internal request uses `POST /api/v1/runners/chat` with `X-Runner-Id` and `X-Runner-Secret`, plus the server token when configured.

Submit the claimed job's `request`. When local tools are needed, the response has status `REQUIRES_ACTION`:

```json
{
  "sessionId":"ses_example",
  "runId":"run_example",
  "status":"REQUIRES_ACTION",
  "toolCalls":[{
    "toolCallId":"call_example",
    "turnId":"turn_example",
    "name":"read",
    "arguments":{"path":"README.md"}
  }]
}
```

Execute those tools on the Runner and return results through the same **internal** endpoint:

```json
{
  "sessionId":"ses_example",
  "runId":"run_example",
  "messages":[{
    "role":"tool",
    "toolCallId":"call_example",
    "status":"SUCCEEDED",
    "content":"The contents of README.md"
  }]
}
```

Repeat until the response is terminal, then report completion. The server verifies the session/run assignment and uses the job's frozen tool catalog; callers cannot override it with another catalog. `ToolDefinition.descriptor()` supplies tool metadata during registration. Tool code and process-local context stay on the Runner.

`toolCallId` matches an unanswered call in session history. Results may arrive together or in parts; the model runs again after all outstanding results arrive. The server executes its own tools before returning local calls and ends that internal request while the Runner executes. The public observer can stay connected throughout the run.

Tool result status is `SUCCEEDED`, `FAILED`, or `UNKNOWN`, with string or JSON content. `UNKNOWN` means an uncertain outcome. User and tool roles cannot be mixed. Outstanding calls must be resolved before starting another user turn.

## Streaming and operation status

Send `Accept: text/event-stream` for streaming. Events contain a JSON-object `payload`; the final `event: response` carries the complete chat response. Public callers display the result; only Runners act on `REQUIRES_ACTION` from the internal endpoint. A final event marks completion of that HTTP exchange.

| Status | Meaning |
| --- | --- |
| `COMPLETED` | The answer is ready. |
| `REQUIRES_ACTION` | The Runner must execute the returned tools; issued by the internal exchange. |
| `CANCELLED` | The operation was stopped. |
| `INTERRUPTED` | The operation did not finish normally. |
| `RUNNING` | A request is active; returned by the inspection endpoint. |

Inspect an operation with `GET /api/v1/sessions/{id}/state`. Request cancellation with `POST /api/v1/runs/{id}/stop`. The Runner also stops its local tools. Results can still be submitted through the internal endpoint for cancelled operations; the server records them without requesting another model response.

## Error handling

| HTTP status | Meaning |
| --- | --- |
| 400 | Invalid message, tool catalog, or result association |
| 401 | Missing or invalid API token |
| 409 | Another request is modifying the same session |
| 413 | Request body exceeds the configured limit |
| 503 | Service capacity is unavailable |

Repeated user messages start new turns; repeated tool results are rejected. A dropped response does not prove that a request or tool failed. Check session history and business effects before retrying. Automatic request replay and exactly-once execution are not part of this protocol.

## Validate protocol fixtures

```sh
npm ci --prefix protocol
npm test --prefix protocol
```

These tests check the request, response, and event fixtures against the JSON Schema.

## Remote runners

Runner dispatch is an optional layer around the conversation API. Runners ship with native tool implementations; only JSON descriptors, messages and results cross the network. Frontends always use the server APIs, whether hosted on the server or client machine.

1. Register with `POST /api/v1/runners`, providing a stable runner ID, display name, and all locally implemented tools. Send a locally generated random secret of at least 32 characters in `X-Runner-Secret`, in addition to the server bearer token. Re-registration requires the same secret. Set `workspaces: true` for a terminal that supports project directories; other scenarios leave it false.
2. Create a session with `runnerId`, optional `workspacePath`, and `enabledTools`. Omit `enabledTools` to select all registered tools; send `[]` to disable local tools. A new project stores these settings. Further sessions with the same `projectId` inherit them. `GET /api/v1/runners/projects/{projectId}` reads settings; `PUT /api/v1/runners/projects/{projectId}/tools` changes `enabledTools` for future runs. Submit one user message to the public `POST /api/v1/chat` endpoint. The server assigns a `runId` if omitted, queues the conversation for its Runner, and returns the terminal result as JSON or streams progress over SSE.
3. The runner calls `GET /api/v1/runners/{id}/poll`. The response waits up to 25 seconds for work. Immediately poll again after a response. Include `?activeRun=<comma-separated-runIds>&availableSlots=<freeSlots>` to report all locally active runs and remaining capacity. Each response contains at most one job, excluding the reported active runs, and may also contain cancellations and directory lookups. With `availableSlots=0`, polling still delivers cancellations and directory lookups but assigns no new work. Keep polling while jobs execute on separate workers. The Java Runner has no fixed conversation limit and always sends `availableSlots=1` to accept the next job; this value does not limit the total number of active runs. If `availableSlots` is omitted, legacy single-job behavior applies: an active run prevents another assignment. Each job carries its project's workspace and frozen tool catalog; validate the directory on the runner and execute only the selected tools, with Full access and no approval exchange.
4. Execute the job's initial request and subsequent tool-result requests using the internal `POST /api/v1/runners/chat` API. This endpoint is for registered Runners only and is not exposed as a user or external Agent operation. Include `X-Runner-Id` and `X-Runner-Secret`. The server requires valid Runner credentials, checks that this Runner owns the claimed job and session, and uses the job's frozen tool catalog. Tool results remain ordinary `role=tool` messages; no tool continuation ID or alternate resume endpoint is introduced.
5. Report local tool start/end events to `POST /api/v1/runners/{id}/events`; model events already originate on the server. Report the terminal ChatResponse to `POST /api/v1/runners/{id}/complete`.

A workspace-capable Runner may advertise `workspaceRoot`, an absolute path on its own machine. When a project omits `workspacePath`, the server allocates `workspaceRoot/user/<projectId>` or `workspaceRoot/agents/<agentId>/<projectId>`. The Java SDK's `LocalWorkspaces` creates these managed directories on first execution; an explicit workspace must already exist. Other language Runners can implement the same convention. Runners without a default root continue to require explicit directories.

The frontend browses directories with `GET /api/v1/runners/{id}/workspaces?path=...`. The server places a `workspaceRequest` (`requestId`, `path`) in the runner's next poll. The runner performs a read-only lookup and posts a `WorkspaceListing` to `POST /api/v1/runners/{id}/workspaces/result`, with its secret. An empty path lists the runner user's home. Native absolute paths are preserved, including Windows paths. Results contain `path`, `parent`, filesystem `roots`, child `directories`, and `truncated`; failures use `error`. The browser request times out after 10 seconds. This optional lookup requires no model call, persistent job, or inbound port.

Inspect dispatch at `GET /api/v1/runners/runs/{runId}`. Subscribe to future SSE events at `GET /api/v1/runners/runs/{runId}/events`; obtain saved history from session details. Existing `POST /api/v1/runs/{runId}/stop` also flags the remote job for cancellation.

CLAIMED jobs remain assigned across disconnects. Receiving the same runId is not permission to execute it again: a runner must retain its local acceptance and result journal. Completion reports can be retried. An interrupted dispatch with pending tool results can be explicitly reopened at `POST /api/v1/runners/{id}/runs/{runId}/recover`; only after inspecting local effects should the runner submit saved results through the normal chat API. This reopens dispatch ownership, not the Agent Harness loop.

Tools are built into each runner. The Harness has no tool download or installation API. Java tools implement `ToolDefinition` and the client SDK's `AgentRunner` handles dispatch; another runtime implements equivalent native interfaces and the same wire messages without MCP. Skills and other server tools execute on the Harness.

## Model selection and image results

`GET /api/v1/models` returns model configuration with credentials masked; `POST /api/v1/models` saves it using its `revision`. `POST /api/v1/models/{id}/test` checks a saved connection. Connection fields match `harness.model.*`, including temperature, timeout and streaming options. The catalog includes `defaultModelId` and read-only `source` (`environment` or `saved`). Saved configuration takes priority over the environment. Capability metadata describes accepted inputs, strengths and limitations; see [schema.json](schema.json).

A public chat can set `modelId`; omitting it selects the current default. Neither Project nor personal Agent conversations have a model-selection tool. The caller selects a model before submission; personal Agents can pass along a user-requested model when delegating work. The selected configuration stays fixed for the run. Runners preserve the assigned request and cannot override it. Tool messages may contain an `images` array in the same format as user attachments. Preserve it when journaling and returning results. The server retains originals and adapts unsupported input types for the selected provider.

Runner registration optionally includes `description`, `platform` and string `capabilities`. These support environment discovery and do not grant permissions or install tools. A Runner with `workspaces=false` can expose device APIs without filesystem paths. Offline jobs remain queued until the Runner reconnects; native mobile clients must handle OS permissions and lifecycle separately.
