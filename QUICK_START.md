# Quick start

The coding demo has three independent processes: the harness server, a coding runner with built-in tools, and the web frontend. Both the runner and frontend connect to the server. Only the runner accesses the local workspace.

## Prerequisites

Install **JDK 8+**, **Maven 3.6.3+**, and **Node.js 22.12+** (Node 20.19 is also supported). Make sure `java`, `mvn`, `node`, and `npm` are on your terminal's `PATH`. You need an OpenAI-compatible model endpoint. SQLite is included.

```sh
git clone https://github.com/differentialmanifold/jagent-harness.git
cd jagent-harness
cp .env.example .env.local
```

Edit `.env.local`:

```dotenv
JAGENT_MODEL=your-model-name
JAGENT_OPENAI_BASE_URL=https://your-provider.example/v1
JAGENT_OPENAI_API_KEY=your-api-key
JAGENT_RUNNER_WORKSPACE=/path/to/your/project
```

## Start with launchers

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

The runner includes all seven coding tool implementations. It defaults to read/search tools; set `JAGENT_RUNNER_FULL_ACCESS=true` to enable write, edit, and bash. Its workspace and permissions cannot be changed from the frontend.

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

Run from the repository root. These commands do not load `.env.local`; set variables in each terminal. Use the same `JAGENT_CLIENT_TOKEN` for server, runner, and the frontend's API proxy. Model credentials belong only to the server.

### 1. Build

```sh
mvn -DskipTests install
npm ci --prefix console
```

The following JAR filenames use version `1.2.0`; update them when using another source version.

### 2. Start the server

```sh
mkdir -p data
export JAGENT_MODEL='your-model-name'
export JAGENT_OPENAI_BASE_URL='https://your-provider.example/v1'
export JAGENT_OPENAI_API_KEY='your-api-key'
export JAGENT_CLIENT_TOKEN='your-shared-token'
export AGENT_SERVER_PORT=18181
export JAGENT_DATASOURCE_URL='jdbc:sqlite:data/agent-server.db'
export JAGENT_CONFIG_ROOT="$PWD/data/server-config"
java -jar examples/server-demo/target/server-demo-1.2.0.jar
```

Wait for the application to report that it has started.

### 3. Start the coding runner

In a second terminal:

```sh
export JAGENT_CLIENT_TOKEN='your-shared-token'
java -jar examples/coding-runner/target/jagent-coding-runner-1.2.0.jar --home data/coding-runner connect http://127.0.0.1:18181
java -jar examples/coding-runner/target/jagent-coding-runner-1.2.0.jar --home data/coding-runner serve --workspace /path/to/your/project --name coding-local
```

Append `--full-access` to the `serve` command when you want to enable file changes and shell commands. No separate tool download or installation is needed.

### 4. Start the frontend

In a third terminal, on either the server or client machine:

```sh
export JAGENT_SERVER_URL='http://127.0.0.1:18181'
export JAGENT_CLIENT_TOKEN='your-shared-token'
npm --prefix console run dev
```

The frontend always forwards API requests to `JAGENT_SERVER_URL`. It has no connection to a runner's local HTTP endpoint. Stop each process with **Ctrl+C**.

## Try a conversation

Open **http://127.0.0.1:5175**, add a project, and select **coding-local** as the execution target. The dialog shows the runner's workspace and enabled tools. Ask the agent to read the README. With full access enabled, try creating a file in a scratch workspace and reading it back.

In **Configuration → Built-in tools**, server tools are configurable. **Client tools** shows the selected runner's tool catalog and schemas. Skills and virtual files are stored and executed through server capabilities. A project using **Server tools only** does not need a runner.

## Place the three parts on different machines

Deploy the server once with HTTPS. On a target machine with Java 8+, copy only `jagent-coding-runner-1.2.0.jar` and use the [Runner guide](examples/coding-runner/README.md) to connect it. The executable already contains its tools.

Host the [web frontend](console/README.md) on the server, on your laptop, or on another machine. Set its `JAGENT_SERVER_URL` to the harness URL. Select the target runner in the same interface wherever the frontend is hosted.

For a source checkout on the client machine, set the remote `JAGENT_SERVER_URL` and `JAGENT_CLIENT_TOKEN`, then run `node scripts/dev.mjs --client-only` to start only the runner and frontend. Model settings are not needed on that machine.

## Configuration reference

| Variable | Purpose / default |
| --- | --- |
| `JAGENT_SERVER_URL` | Harness URL used by runner and frontend; `http://127.0.0.1:18181` |
| `JAGENT_CLIENT_TOKEN` | Shared server API token |
| `AGENT_SERVER_PORT` | Server port; `18181` |
| `JAGENT_CONSOLE_PORT` | Frontend launcher port; `5175` |
| `JAGENT_CONSOLE_HOST` | Frontend launcher bind address; `127.0.0.1` |
| `JAGENT_RUNNER_WORKSPACE` | Workspace on the runner machine; repository root in launchers |
| `JAGENT_RUNNER_HOME` | Runner identity and journal; `data/coding-runner` in launchers |
| `JAGENT_RUNNER_NAME` | Display name; `coding-local` |
| `JAGENT_RUNNER_FULL_ACCESS` | Enable write, edit, and bash; `false` |
| `JAGENT_RG_PATH` | Optional path to ripgrep; search tools also have a Java fallback |

The standalone runner defaults to `~/.jagent-runner` unless `--home` or `JAGENT_RUNNER_HOME` is provided. One identity binds to one workspace. Use another home for another workspace or server.

## Troubleshooting

- **Runner missing:** check that it uses the same server URL/token as the frontend. Open the project dialog again after the runner comes online.
- **Write or bash missing:** enable full access on the runner, finish outstanding work, and restart it. The frontend cannot elevate local permissions.
- **Port occupied:** stop the previous server/frontend or change its port. The runner uses no listening port.
- **401 response:** check the shared API token in all three processes.
- **Remote connection fails:** use HTTPS for a remote server; loopback HTTP is allowed for local development.
- **Interrupted task:** inspect local effects and follow [recovery instructions](examples/coding-runner/README.md#state-cancellation-and-recovery).
