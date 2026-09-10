# Quick start

The coding demo runs three components: the Java harness server, a coding client that executes local tools, and the web console. Start them together, start them separately, or run the underlying commands yourself.

## Prerequisites

Install **JDK 8+**, **Maven 3.6.3+**, and **Node.js 22.12+** (Node 20.19 is also supported). Make sure `java`, `mvn`, `node`, and `npm` are available in your terminal. You also need an OpenAI-compatible model endpoint. SQLite is included; no database service is required.

Clone the repository and enter its root directory:

```sh
git clone https://github.com/differentialmanifold/jagent-harness.git
cd jagent-harness
```

## Start with launchers

Copy `.env.example` to `.env.local` (`cp .env.example .env.local` on macOS/Linux, or `Copy-Item .env.example .env.local` in PowerShell). Edit the model settings:

```dotenv
JAGENT_MODEL=your-model-name
JAGENT_OPENAI_BASE_URL=https://your-provider.example/v1
JAGENT_OPENAI_API_KEY=your-api-key
```

Run all three components:

```sh
node scripts/dev.mjs
```

Or use a separate terminal for each component, in this order:

| Component | Command | Default address |
| --- | --- | --- |
| Harness server | `node scripts/start-server.mjs` | `http://127.0.0.1:18181` |
| Coding client | `node scripts/start-client.mjs` | `http://127.0.0.1:18180` |
| Frontend | `node scripts/start-frontend.mjs` | `http://127.0.0.1:5175` |

Each launcher starts only the selected component. Java launchers build the required modules; the frontend launcher installs npm dependencies when missing and does not require Maven or a JDK. The client waits for its configured server to become ready. The frontend can start independently; its API becomes available when the client is running.

Add `--skip-build` to reuse existing Java JARs after a successful build; omit it after changing Java code. Use `--help` to see options. Avoid building the same checkout from multiple terminals at once: wait for the server to finish starting before launching the client. Press **Ctrl+C** in a terminal to stop only the processes started by that launcher.

### Double-click shortcuts

The `scripts` directory includes matching shortcuts for both platforms:

| Start | Windows | macOS |
| --- | --- | --- |
| Everything | `dev.cmd` | `dev.command` |
| Server only | `start-server.cmd` | `start-server.command` |
| Client only | `start-client.cmd` | `start-client.command` |
| Frontend only | `start-frontend.cmd` | `start-frontend.command` |

After configuring `.env.local`, double-click the appropriate file. Each shortcut opens in a terminal and finds the repository root automatically. Keep the terminal open while the component is running. The required commands must be on your terminal's `PATH`; shortcuts do not install Java, Maven, or Node. If a downloaded archive has lost macOS executable permissions, run `chmod +x scripts/*.command` once.

## Start manually without launchers

Run these commands from the repository root. Direct Java and npm commands **do not load `.env.local`**. Set environment variables in the terminal running each component as shown below. Use the same `JAGENT_CLIENT_TOKEN` for server and client; model credentials belong only to the server.

### 1. Build once

```sh
mvn -DskipTests install
npm ci --prefix console
```

This builds the libraries and both executable example JARs. The examples below use version `1.1.0`; after changing project versions, use the corresponding JAR filenames.

### 2. Start the server in terminal 1

macOS / Linux:

```sh
mkdir -p data
export JAGENT_MODEL='your-model-name'
export JAGENT_OPENAI_BASE_URL='https://your-provider.example/v1'
export JAGENT_OPENAI_API_KEY='your-api-key'
export JAGENT_CLIENT_TOKEN='your-shared-token'
export AGENT_SERVER_PORT=18181
export JAGENT_DATASOURCE_URL='jdbc:sqlite:data/agent-server.db'
export JAGENT_CONFIG_ROOT="$PWD/data/server-config"
java -jar examples/server-demo/target/server-demo-1.1.0.jar
```

Wait for Spring Boot to report that the application has started.

### 3. Start the client in terminal 2

macOS / Linux:

```sh
export JAGENT_SERVER_URL='http://127.0.0.1:18181'
export JAGENT_CLIENT_TOKEN='your-shared-token'
export CODING_CLIENT_PORT=18180
java -jar examples/coding-java/target/jagent-coding-java-1.1.0.jar
```

### 4. Start the frontend in terminal 3

macOS / Linux:

```sh
export JAGENT_API_TARGET='http://127.0.0.1:18180'
npm --prefix console run dev:coding
```

The frontend must point at the **coding client**, which adds its local tools and forwards server requests. Pointing directly at the harness server does not load coding tools. Stop each process with **Ctrl+C** in its terminal.

## Try a conversation

Open **http://127.0.0.1:5175**, create a project with a local workspace, and start a conversation. Ask the agent to read the README. Then, in a scratch workspace, ask it to create a file and read it back. The resulting file is on the client machine.

In **Configuration → Built-in tools**, server tools have configurable selection and debugging controls. The **Client tools** section shows the tools registered by the connected coding client, including their input schemas; client execution and permissions remain under the client's control.

## Connect the local client to a remote harness

Set `JAGENT_SERVER_URL` and `JAGENT_CLIENT_TOKEN` in `.env.local`, then run:

```sh
node scripts/dev.mjs --client-only
```

Only the coding client and frontend start locally. Alternatively, run `start-client.mjs` and `start-frontend.mjs` in separate terminals, or follow manual steps 3 and 4 with the remote server URL. The workspace and approval controls remain local. See [Server integration](jagent-harness-sdk/README.md) for deploying the harness.

## Configuration

Launchers read `.env.local`; existing shell environment variables take precedence. Use the model API base URL, usually ending in `/v1`, rather than the full chat-completions URL.

| Setting | Default / purpose |
| --- | --- |
| `AGENT_SERVER_PORT` | `18181` |
| `CODING_CLIENT_PORT` | `18180` |
| `JAGENT_CONSOLE_PORT` | `5175`; launcher frontend port and the client's trusted UI origin |
| `JAGENT_SERVER_URL` | `http://127.0.0.1:18181` in the example environment file |
| `JAGENT_CLIENT_TOKEN` | Shared server/client API token |
| `JAGENT_API_TARGET` | Frontend-only launcher API target; defaults to the local coding client |
| `JAGENT_DATASOURCE_URL` | Launcher default: `jdbc:sqlite:data/agent-server.db` |
| `JAGENT_CONFIG_ROOT` | Launcher default: `data/server-config` |

The frontend uses port `5175` by default. To change it with launchers, set `JAGENT_CONSOLE_PORT` for both client and frontend. For manual startup, also pass `-- --port YOUR_PORT` to the npm command. When changing a backend port, update its callers' URLs too. Conversation data persists between launches. The client only needs the server URL and token, not model credentials.

## Troubleshooting

- **Port occupied:** stop an existing demo or choose different backend ports. Do not mix an all-in-one launcher with already running components on the same ports.
- **Client tools missing:** check that the frontend targets the coding client on port `18180`, not the harness server on `18181`.
- **Model request rejected:** check the model name, API base URL, and key. The server terminal contains the provider's error.
- **HTTP 401:** configure the same API token on server and client, then restart after environment changes.
- **Maven artifact missing:** run `mvn install` at the repository root before building an independent consumer.
- **Connection lost during execution:** inspect the conversation and local effects before retrying. Requests and tool execution are not automatically replayed.
