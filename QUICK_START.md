# Quick start

Use the [README](README.md#try-it-locally) to configure the model and launch the coding demo. It demonstrates the project's central boundary: the harness runs on the server, while the SDK executes tools in a local workspace.

## Run the demo

From the repository root:

```sh
node scripts/dev.mjs
```

Open **http://127.0.0.1:5175**, create a project with a local workspace, and start a conversation. Ask the agent to read the README. Then, in a scratch workspace, ask it to create a file and read it back. The conversation shows the tool calls and results; the resulting file is on the client machine.

The launcher builds the Java applications and installs console dependencies when needed. After a successful build, `--skip-build` makes restarts faster; omit it after changing Java code. Press **Ctrl+C** to stop the launched services.

On Windows, copy `.env.example` with your editor or `Copy-Item .env.example .env.local` in PowerShell. The launcher supports Windows, macOS, and Linux. Node 20.19 is also supported alongside Node 22.12+.

## Connect the local client to a remote harness

Set `JAGENT_SERVER_URL` and `JAGENT_CLIENT_TOKEN` in `.env.local`, then run:

```sh
node scripts/dev.mjs --client-only
```

Only the coding client and console start locally. The workspace and approval controls remain here; the existing server owns model access and conversation state. See [Server integration](jagent-harness-sdk/README.md) for deploying that server.

## Configuration

The launcher reads `.env.local`; existing shell environment variables take precedence. Model settings are `JAGENT_MODEL`, `JAGENT_OPENAI_BASE_URL`, and `JAGENT_OPENAI_API_KEY`. Use the API base URL, usually ending in `/v1`, rather than the full chat-completions URL.

| Setting | Default / purpose |
| --- | --- |
| `AGENT_SERVER_PORT` | `18181` |
| `CODING_CLIENT_PORT` | `18180` |
| `JAGENT_SERVER_URL` | `http://127.0.0.1:18181` in the example environment file |
| `JAGENT_CLIENT_TOKEN` | Shared server/client API token |
| `JAGENT_DATASOURCE_URL` | Launcher default: `jdbc:sqlite:data/agent-server.db` |
| `JAGENT_CONFIG_ROOT` | Launcher default: `data/server-config` |

The console uses port `5175`. If you change the server port, update its URL too. Conversation data persists between launches. The client only needs the server URL and token, not model credentials.

## Troubleshooting

- **Port occupied:** stop an existing demo or choose different backend ports. Keep the server URL in sync; the console needs port 5175.
- **Model request rejected:** check the model name, API base URL, and key. The server terminal contains the provider's error.
- **HTTP 401:** configure the same API token on server and client, then restart after environment changes.
- **Maven artifact missing:** run `mvn install` at the repository root before building an independent consumer.
- **Connection lost during execution:** inspect the conversation and local effects before retrying. Requests and tool execution are not automatically replayed.
