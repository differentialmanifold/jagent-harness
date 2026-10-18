# Quick start

The coding demo has three independent processes: the harness server, a coding runner with built-in tools, and the web frontend. Both the runner and frontend connect to the server. Only the runner accesses the local workspace.

## Prerequisites

Install **JDK 8+**, **Maven 3.6.3+**, and **Node.js 22.12+** (Node 20.19 is also supported). Make sure `java`, `mvn`, `node`, and `npm` are on your terminal's `PATH`. You need an OpenAI-compatible model endpoint. SQLite is included.

```sh
git clone https://github.com/differentialmanifold/jagent-harness.git
cd jagent-harness
```

Choose either the launchers or the manual steps below.

## Start with launchers

Copy `.env.example` to `.env.local` and set only your model connection:

```sh
cp .env.example .env.local
```

```dotenv
JAGENT_MODEL=your-model-name
JAGENT_OPENAI_BASE_URL=https://your-provider.example/v1
JAGENT_OPENAI_API_KEY=your-api-key
```

Start everything:

```sh
node scripts/dev.mjs
```

Or use a separate terminal for each part, in this order:

| Part | Command | Address |
| --- | --- | --- |
| Harness server | `node scripts/start-server.mjs` | `http://127.0.0.1:18181` |
| Coding runner | `node scripts/start-client.mjs` | Outbound connection to the server; no listening port |
| Web frontend | `node scripts/start-frontend.mjs` | `http://127.0.0.1:5175`, with `/api` forwarded to the server |

The runner includes all seven coding tools. Select the workspace and enabled tools per project in the frontend. Selected tools run with Full access, without approval prompts.

Each launcher starts only its own component. Java launchers build the required modules; `--skip-build` reuses existing JARs. The runner launcher registers with the server before starting its receive loop. The frontend launcher installs npm dependencies when missing and does not require Java or Maven. It can start before the server is ready.

Avoid building the same checkout from multiple terminals simultaneously. Use `--help` for options. **Ctrl+C** stops only the processes started by that launcher.

### Double-click shortcuts

After configuring `.env.local`, use the matching shortcut:

| Start | Windows | macOS |
| --- | --- | --- |
| Everything | `scripts/dev.cmd` | `scripts/dev.command` |
| Server | `scripts/start-server.cmd` | `scripts/start-server.command` |
| Coding runner | `scripts/start-client.cmd` | `scripts/start-client.command` |
| Frontend | `scripts/start-frontend.cmd` | `scripts/start-frontend.command` |

Each shortcut finds the repository root automatically. Keep its terminal open while running. The required commands must be on `PATH`. If an archive loses executable permissions on macOS, run `chmod +x scripts/*.command` once.

## Start manually without launchers

Start with the local defaults: server on **18181**, frontend on **5175**, and a runner that connects to the local server automatically. Only the model connection needs configuration. Run all commands from the repository root.

### 1. Build once

```sh
mvn -DskipTests package
npm ci --prefix console
```

### 2. Terminal 1: server

Set your model name, API endpoint, and API key, then start the server:

```sh
export JAGENT_MODEL='your-model-name'
export JAGENT_OPENAI_BASE_URL='https://your-provider.example/v1'
export JAGENT_OPENAI_API_KEY='your-api-key'
java -jar examples/server-demo/target/server-demo-1.2.1.jar
```

Omit the API key if your model endpoint does not require one. Wait for the server to report that it has started. SQLite storage is created automatically as `agent-server.db` in the working directory.

### 3. Terminal 2: runner

```sh
java -jar examples/coding-runner/target/jagent-coding-runner-1.2.1.jar
```

No arguments or environment variables are needed. Wait for `Runner <machine-name> online`. The runner registers all coding tools and keeps receiving work. Choose project directories in the frontend after startup.

### 4. Terminal 3: frontend

```sh
npm --prefix console run dev
```

Open **http://127.0.0.1:5175**. Keep all three terminals running; **Ctrl+C** stops each process.

These manual Java commands do not load `.env.local`. If you previously configured custom addresses or a token in your shell, those settings override the defaults. Remote deployment and other optional settings are listed in [Configuration reference](#configuration-reference).

### Run the Java processes in IntelliJ IDEA

Import the root `pom.xml` as a Maven project. Run `ServerDemoApplication.main()` with the model environment variables above, then run `RunnerMain.main()` with no program arguments. Start the frontend with the same npm command in IDEA's terminal. Only the server needs model credentials.

## Try a conversation

Open **http://127.0.0.1:5175**, add a project, and select the online runner as the execution target (**coding-local** with launchers, or your machine name with manual startup). Browse or enter a directory on that terminal. New projects enable all registered client tools by default. Ask the agent to read the README. With write enabled, try creating a file in a scratch workspace and reading it back.

In **Configuration → Built-in tools**, server tools are configurable. **Client tools** lets you change the current project's enabled tools and inspect their schemas. Changes apply to the next run; selecting no client tools keeps local execution disabled. Skills and virtual files are stored and executed through server capabilities. A project using **Server tools only** does not need a runner.

## Configuration reference

| Variable | Purpose / default |
| --- | --- |
| `JAGENT_MODEL` | Model name; required on the server |
| `JAGENT_OPENAI_BASE_URL` | OpenAI-compatible API endpoint; required on the server |
| `JAGENT_OPENAI_API_KEY` | Model API key, if required by the provider; server only |
| `AGENT_BIND_ADDRESS` | Server bind address; `127.0.0.1` for local startup |
| `JAGENT_DATASOURCE_URL` | SQLite URL; `jdbc:sqlite:agent-server.db` directly, `jdbc:sqlite:data/agent-server.db` with launchers |
| `JAGENT_CONFIG_ROOT` | Server configuration root; `./data/server-config` |
| `JAGENT_SERVER_URL` | Harness URL used by runner and frontend; `http://127.0.0.1:18181` |
| `JAGENT_CLIENT_TOKEN` | Optional shared server API token; empty for local startup. If set, use the same value for all three processes. |
| `AGENT_SERVER_PORT` | Server port; `18181` |
| `JAGENT_CONSOLE_PORT` | Frontend launcher port; `5175` |
| `JAGENT_CONSOLE_HOST` | Frontend launcher bind address; `127.0.0.1` |
| `JAGENT_RUNNER_HOME` | Runner identity and journal; `data/coding-runner` in launchers |
| `JAGENT_RUNNER_NAME` | Display name; machine hostname for direct startup, `coding-local` in launchers |
| `JAGENT_RG_PATH` | Optional path to ripgrep; search tools also have a Java fallback |

The standalone runner defaults to `~/.jagent-runner` unless `--home` or `JAGENT_RUNNER_HOME` is provided. One identity represents one terminal and can serve multiple project directories. Use another home for another terminal or server.

### Connect to a remote server

Deploy the server with HTTPS. Set its bind address as needed for your host or reverse proxy, and set `JAGENT_CLIENT_TOKEN` for authenticated access. Model credentials stay on the server.

On the runner machine, set the remote address and token, then use the same startup command:

```sh
export JAGENT_SERVER_URL='https://agent.example.com'
export JAGENT_CLIENT_TOKEN='your-shared-token'
java -jar jagent-coding-runner-1.2.1.jar
```

The standalone JAR includes the SDK and coding tools. See the [Runner guide](examples/coding-runner/README.md) for distribution and recovery.

On the frontend host, set the same `JAGENT_SERVER_URL` and `JAGENT_CLIENT_TOKEN` before running `npm --prefix console run dev`. The frontend can run on either machine. See [frontend hosting](console/README.md) for a production build.

With a source checkout, put these optional settings in `.env.local` and run `node scripts/dev.mjs --client-only` to start the runner and frontend against the remote server.

## Troubleshooting

- **Runner missing:** check that it uses the same server URL/token as the frontend. Open the project dialog again after the runner comes online.
- **Write or bash missing:** enable it in the project’s Client tools selection. Tools use the runner OS account’s permissions; the selected directory is a working directory, not a sandbox.
- **Port occupied:** stop the previous server/frontend or change its port. The runner uses no listening port.
- **401 response:** check the shared API token in all three processes.
- **Remote connection fails:** plain HTTP to a remote server is accepted with an unencrypted-traffic warning; prefer HTTPS so the shared token and job traffic stay encrypted.
- **Interrupted task:** inspect local effects and follow [recovery instructions](examples/coding-runner/README.md#state-cancellation-and-recovery).
