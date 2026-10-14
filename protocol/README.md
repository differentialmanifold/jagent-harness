# HTTP protocol

Use this protocol to connect a custom application or language SDK to JAgentHarness. The server exposes JSON APIs and server-sent events. Java clients can use [jagent-client-java](../sdk/java/README.md).

Java tool contracts and protocol DTOs are provided by `io.github.differentialmanifold:jagent-api`. The JSON protocol remains independent of Java.

Reference materials: [OpenAPI](openapi.json), [JSON Schema](schema.json), and [fixtures](fixtures/). The API prefix is `/api/v1`. Send `Authorization: Bearer <token>` when authentication is configured.

The JSON files in `fixtures/` are protocol test samples: user messages, tool results, responses, stream events, runner registration, and polling. `schema.test.mjs` checks them against the JSON Schema during CI and release validation. They are not runtime configuration or installed tool implementations. Run `npm ci --prefix protocol` and `npm test --prefix protocol` from the repository root to validate them.

## Start a conversation

Create a session with `POST /api/v1/sessions`:

```json
{"title":"My assistant"}
```

Use the returned `sessionId` in `POST /api/v1/chat`. A request contains either one user message or a list of tool results:

```json
{
  "sessionId": "ses_example",
  "messages": [{"role":"user","content":"Read README.md"}],
  "clientTools": [{
    "name":"read_file",
    "description":"Read a file in the current workspace",
    "parameters":{
      "type":"object",
      "properties":{"path":{"type":"string"}},
      "required":["path"]
    }
  }]
}
```

Set `clientTools` to an empty list when all tools run on the server. Tool metadata contains `name`, `description`, and a JSON Schema `parameters` object. In Java, `ToolDefinition.descriptor()` supplies this shape; implementations and local context are never transmitted.

## Execute client tools

When local execution is needed, the response has status `REQUIRES_ACTION`:

```json
{
  "sessionId":"ses_example",
  "runId":"run_example",
  "status":"REQUIRES_ACTION",
  "toolCalls":[{
    "toolCallId":"call_example",
    "turnId":"turn_example",
    "name":"read_file",
    "arguments":{"path":"README.md"}
  }]
}
```

Execute the listed tools and submit their results to the same `POST /api/v1/chat` endpoint:

```json
{
  "sessionId":"ses_example",
  "messages":[{
    "role":"tool",
    "toolCallId":"call_example",
    "status":"SUCCEEDED",
    "content":"The contents of README.md"
  }],
  "clientTools":[{
    "name":"read_file",
    "description":"Read a file in the current workspace",
    "parameters":{
      "type":"object",
      "properties":{"path":{"type":"string"}},
      "required":["path"]
    }
  }]
}
```

Continue until the response is terminal. Include the tool catalog needed for the next model call on every request. Optional `clientInstructions` supplies application-specific guidance. Java SDK clients carry both automatically.

`sessionId` selects the conversation, and `toolCallId` matches each result to an unanswered client tool call. `runId` is optional on submissions and supports UI tracking and cancellation. Results may be submitted together or in parts; the model is called only after all outstanding results arrive. The server completes its own tools before returning client calls and does not hold a request open while the client executes them.

Tool results use `SUCCEEDED`, `FAILED`, or `UNKNOWN`, with string or JSON content. UNKNOWN indicates an uncertain outcome. User and tool roles cannot be mixed in a request. Outstanding calls must be resolved before sending another user message.

## Streaming and operation status

Send `Accept: text/event-stream` for streaming. Events contain a JSON-object `payload`; the final `event: response` carries the complete chat response. Wait for that response before deciding whether to execute tools or finish the interaction.

| Status | Meaning |
| --- | --- |
| `COMPLETED` | The answer is ready. |
| `REQUIRES_ACTION` | The client must execute the returned tools. |
| `CANCELLED` | The operation was stopped. |
| `INTERRUPTED` | The operation did not finish normally. |
| `RUNNING` | A request is active; returned by the inspection endpoint. |

Inspect an operation with `GET /api/v1/sessions/{id}/state`. Request cancellation with `POST /api/v1/runs/{id}/stop`. The client must also stop its own local tools. Results can still be submitted for cancelled operations; the server records them without requesting another model response.

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
2. Create a session with `runnerId`, optional `workspacePath`, and `enabledTools`. Omit `enabledTools` to select all registered tools; send `[]` to disable local tools. A new project stores these settings. Further sessions with the same `projectId` inherit them. `GET /api/v1/runners/projects/{projectId}` reads settings; `PUT /api/v1/runners/projects/{projectId}/tools` changes `enabledTools` for future runs. Submit one user message and a `runId` to `POST /api/v1/runners/chat` to queue a conversation and stream progress.
3. The runner calls `GET /api/v1/runners/{id}/poll`. The response waits up to 25 seconds for work. Immediately poll again after a response. While executing a job, include `?activeRun=<runId>` to receive cancellation and directory lookups without taking another job. Each job carries its project's workspace and frozen tool catalog; validate the directory on the runner and execute only the selected tools, with Full access and no approval exchange.
4. Execute the job's initial request and subsequent tool-result requests using the existing `POST /api/v1/chat` API. Include `X-Runner-Id` and `X-Runner-Secret`. The server checks session ownership and uses the job's frozen tool catalog. Tool results remain ordinary `role=tool` messages; no tool continuation ID or alternate resume endpoint is introduced.
5. Report local tool start/end events to `POST /api/v1/runners/{id}/events`; model events already originate on the server. Report the terminal ChatResponse to `POST /api/v1/runners/{id}/complete`.

The frontend browses directories with `GET /api/v1/runners/{id}/workspaces?path=...`. The server places a `workspaceRequest` (`requestId`, `path`) in the runner's next poll. The runner performs a read-only lookup and posts a `WorkspaceListing` to `POST /api/v1/runners/{id}/workspaces/result`, with its secret. An empty path lists the runner user's home. Native absolute paths are preserved, including Windows paths. Results contain `path`, `parent`, filesystem `roots`, child `directories`, and `truncated`; failures use `error`. The browser request times out after 10 seconds. This optional lookup requires no model call, persistent job, or inbound port.

Inspect dispatch at `GET /api/v1/runners/runs/{runId}`. Subscribe to future SSE events at `GET /api/v1/runners/runs/{runId}/events`; obtain saved history from session details. Existing `POST /api/v1/runs/{runId}/stop` also flags the remote job for cancellation.

CLAIMED jobs remain assigned across disconnects. Receiving the same runId is not permission to execute it again: a runner must retain its local acceptance and result journal. Completion reports can be retried. An interrupted dispatch with pending tool results can be explicitly reopened at `POST /api/v1/runners/{id}/runs/{runId}/recover`; only after inspecting local effects should the runner submit saved results through the normal chat API. This reopens dispatch ownership, not the Agent Harness loop.

Tools are built into each runner. The Harness has no tool download or installation API. Java tools implement `ToolDefinition` and the client SDK's `AgentRunner` handles dispatch; another runtime implements equivalent native interfaces and the same wire messages without MCP. Skills and other server tools execute on the Harness.
