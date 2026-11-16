# Quick start

[Project overview](README.md) · [Documentation](docs/README.md)

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
| Web frontend | `node scripts/start-frontend.mjs` | `http://127.0.0.1:5175`, with `/api` forwarded to the server |
| Coding runner | `node scripts/start-client.mjs --mainbot` | Opens your Console; no listening port |

The Runner opens the Console automatically. Talk to **mainbot**, created for your personal identity. The first Runner binds to it; subsequent Runners keep that binding unchanged. The server writes local credentials to `data/user.env`, which the launcher reads automatically. The all-in-one launcher also registers this checkout for source-based help; separate startup can use `--mainbot` for that setup. Restarts preserve your identity, conversations and settings.

The runner includes all seven coding tools. Choose an existing workspace or leave it empty for a new project directory. Select enabled tools per project in the frontend. Selected tools run with Full access, without approval prompts.

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

## Run a prebuilt application

Download `jagent-<version>.zip` from a release and extract it. Install Java 8+ and Node.js 22.12+, copy `.env.example` to `.env.local`, and configure the model as above. Run `node scripts/dev.mjs` or the included Windows/macOS shortcut. Open **http://127.0.0.1:5175**.

The package contains the server, Coding Runner, built Console and matching source. It needs no Git, Maven or npm download at runtime. The Console uses a small Node.js static server and API proxy; model connectivity is still required. mainbot can inspect the bundled source through its ordinary Project tools. Keep the package's `data/` directory when restarting.

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
java -jar examples/server-demo/target/server-demo-2.3.0.jar
```

Omit the API key if your model endpoint does not require one. Wait for the server to report that it has started. SQLite storage is created automatically as `agent-server.db` in the working directory.

### 3. Terminal 2: frontend

```sh
npm --prefix console run dev
```

### 4. Terminal 3: runner

```sh
java -jar examples/coding-runner/target/jagent-coding-runner-2.3.0.jar
```

No arguments or extra environment variables are needed. The Runner reads the server-generated `data/user.env`, authenticates, and opens **http://127.0.0.1:5175** with your mainbot ready to chat. Choose project directories in the Console. To enable source-based help, optionally set the absolute JAgentHarness source directory in mainbot's **Configuration → General & instructions**. Keep all three terminals running; **Ctrl+C** stops each process.

These manual Java commands do not load `.env.local`. If you previously configured custom addresses or a token in your shell, those settings override the defaults. Remote deployment and other optional settings are listed in [Configuration reference](#configuration-reference).

### Run the Java processes in IntelliJ IDEA

Import the root `pom.xml` as a Maven project. Use the repository root as the working directory for both Java run configurations. Run `ServerDemoApplication.main()` with the model environment variables above, start the frontend with the npm command, then run `RunnerMain.main()` with no program arguments. Only the server needs model credentials.

## Try a conversation

Open **http://127.0.0.1:5175**, add a project, and select the online runner as the execution target (**coding-local** with launchers, or your machine name with manual startup). Give the project a name and leave the workspace empty to create a new directory automatically, or select an existing repository. New projects enable all registered client tools by default. Try: “Create a hello.txt file with a greeting, then read it back.”

Open **Projects → Configuration → Tools** to configure a project’s Runner tools. **Client tools** lets you change the current project's enabled tools and inspect their schemas. Changes apply to the next run; selecting no client tools keeps local execution disabled. Skills and virtual files are stored and executed through server capabilities. A project using **Server tools only** does not need a runner.

## Try personal Agents

Open **Agents** and follow the [hands-on Agent guide](docs/guides/agents.md) ([简体中文](docs/guides/agents.zh-CN.md)). Talk to **mainbot** and send the guide's short TODO request. It is the initial primary chat entry point; its Runner is the default for Agents you create. No workspace setup is needed. Memory, Skills, and follow-up tasks are optional next steps. Use the same running services; no extra packages or browser tests are needed. Feishu is optional.

For everyday use, continue with [Projects](docs/guides/projects.md) or [Personal Agents](docs/guides/agents.md). [Configuration and Skills](docs/guides/configuration.md) explains defaults and project/Agent settings. All feature guides and integration references are listed in the [documentation index](docs/README.md).

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
| `JAGENT_USER_TOKEN` | Harness-issued personal token; Runner/API clients only. Local startup reads `data/user.env` automatically. |
| `JAGENT_USER_CREDENTIALS` | Runner credential file; defaults to `data/user.env` |
| `JAGENT_LOCAL_TOKEN_FILE` | Server output for the first local credential; defaults to `data/user.env` |
| `JAGENT_CONSOLE_URL` | Browser-facing Console address; set when different from the Runner's Harness address |
| `JAGENT_OPEN_BROWSER` | Set to `false` for an unattended Runner |
| `AGENT_SERVER_PORT` | Server port; `18181` |
| `JAGENT_CONSOLE_PORT` | Frontend launcher port; `5175` |
| `JAGENT_CONSOLE_HOST` | Frontend launcher bind address; `127.0.0.1` |
| `JAGENT_RUNNER_DISTRIBUTION_DIR` | Server-side offline Runner packages; `./data/runners` directly, build output with launchers. See [local distribution](examples/coding-runner/README.md#distribute-inside-your-network). |
| `JAGENT_SOURCE_WORKSPACE` | Optional source directory on the launcher’s Runner; defaults to the checkout root or the packaged `sources/` directory |
| `JAGENT_RUNNER_HOME` | Runner identity and journal; `data/coding-runner` in launchers |
| `JAGENT_RUNNER_NAME` | Display name; machine hostname for direct startup, `coding-local` in launchers |
| `JAGENT_RG_PATH` | Optional path to ripgrep; search tools also have a Java fallback |

The standalone runner defaults to `~/.jagent-runner` unless `--home` or `JAGENT_RUNNER_HOME` is provided. One identity represents one terminal and can serve multiple project directories. The SDK keeps a separate identity and journal for each server and user under that home; do not copy an identity to another terminal. See [Runner state](examples/coding-runner/README.md#state-cancellation-and-recovery).

### Connect to a remote server

Deploy the Harness and frontend on the remote host. The operator issues a user credential; only the Runner needs to run on your computer:

```sh
export JAGENT_SERVER_URL='http://harness.example.com'
export JAGENT_USER_CREDENTIALS='/path/to/my-user.env'
node scripts/start-client.mjs
```

The Runner opens your remote Console automatically. Set `JAGENT_CONSOLE_URL` if its browser-facing address differs. HTTP is accepted with a warning; HTTPS is recommended because HTTP exposes credentials and tool traffic to interception or modification.

See [Personal access and remote deployment](docs/guides/access.md) for issuing credentials, multiple users, revocation, proxy configuration and browser access. The remote frontend must forward each browser's cookie; it must not inject a shared server token.
