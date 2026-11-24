# Quick start

[Project overview](README.md) · [Documentation](docs/README.md)

| Where you run it | What to start | Command |
| --- | --- | --- |
| One computer | Harness, web Console and Coding Runner | `node scripts/start.mjs local` |
| Harness host | Java service | `node scripts/start.mjs harness` |
| Frontend host | Web Console and API proxy | `node scripts/start.mjs frontend` |
| Machine with the files or tools | Node.js Coding Runner | `jagent runner --server <url> --credentials <file>` |

## Install and start

Use a checkout or an extracted application ZIP built from this revision. The local stack needs Java 8+ and Node.js. Use a maintained Node.js LTS release (22.12+). Existing Node.js 20 installations work from 20.19.0. Building from source also requires a JDK and Maven 3.6.3+. Windows users need Git for Windows to use the Runner's `bash` tool. [Release assets](https://github.com/differentialmanifold/jagent-harness/releases)

1. Copy `.env.example` to `.env.local` next to the `scripts` directory. Enter your provider's model ID, OpenAI-compatible base URL and API key if required.

   ```dotenv
   JAGENT_MODEL=your-model-name
   JAGENT_OPENAI_BASE_URL=https://your-provider.example/v1
   JAGENT_OPENAI_API_KEY=your-api-key
   ```

2. Run `node scripts/start.mjs local` from the project root, or double-click the macOS or Windows shortcut listed in the script guide. From source, the launcher builds the Harness and Runner and installs Console dependencies on first use. The release ZIP includes all three components. [Script guide](scripts/README.md)
3. The Runner opens a signed-in Console. Select **Agents → mainbot → Chat**. Use Ctrl+C to stop this stack. Running the same command again reopens the same local workspace.

## Give mainbot its first job

> Make a release checklist in a new workspace. Write “Run tests” and “Write release notes” to CHECKLIST.md. Mark the first item complete, read it back, and tell me its path and which item is still open.

Open **mainbot → Projects** to see the tool calls and results. Follow up in the same chat with “Mark the release notes complete.” For a longer task, **mainbot → Tasks** shows what it is tracking. [Personal Agent walkthrough](docs/guides/agents.md)

## Use a remote Harness

On the Harness host, configure the model as above and run `node scripts/start.mjs harness`. An operator can also run the published `server-demo-<version>.jar` directly with `java -jar` and the model variables exported in the service environment. The JAR runs with Java alone.

On the frontend host, set `JAGENT_SERVER_URL` to the Harness address reachable **from that host**, and run `node scripts/start.mjs frontend`. Publish the Console and its `/api/` proxy at one origin. The packaged frontend serves prebuilt files. The checkout starts Vite for development. A separate web server can host the Console release archive. [Console hosting](console/README.md)

On the execution machine, install the Runner once. Run:

```sh
npm install --global jagent
jagent runner --server https://harness.example.com --credentials /path/to/my-user.env
```

Use the public Console origin for `--server` if it proxies `/api/`. If the Runner connects directly to the Harness, add `--console <browser-facing-url>`. Have the operator issue the credential on the Harness host. Only the execution machine needs the Runner. [Credentials and remote deployment](docs/guides/access.md)

For offline installation, install the built tarball with `npm install --global /path/to/jagent-0.1.0.tgz`. To produce it from a checkout:

```sh
cd sdk/node
npm ci
npm pack
```

Run `jagent version` to check the installed Runner version. The global `jagent` command starts a Runner by default. Use `jagent open --link` for a fresh browser link, `jagent pair CODE` to approve another device, and `npm uninstall --global jagent` to remove the command. Runner identities and workspaces persist. [Runner guide](sdk/node/README.md)

## Linux servers and existing services

On a headless host, pass `--no-browser` to skip opening the Console. Project browser automation is configured by the Harness Skills. Run the Runner service under the OS account that owns the workspace. Use the absolute path from `command -v jagent` and include Node on the service's PATH. Open the Console remotely and approve the pairing code on the Runner machine. [Pairing with a service Runner](docs/guides/access.md#pair-with-a-linux-service-runner)

When upgrading an existing installation, stop the previous Runner first. Use its original Harness origin, user credential and `--home` so Projects and mainbot retain their Runner ID. For the previous global all-in-one installation, set `JAGENT_STATE_DIR` to the original state directory (normally `~/.jagent`) before starting the new local stack. When replacing a manually installed global launcher, remove it using its original distribution's uninstaller and ensure the npm bin directory is on PATH.

## Choose a database

SQLite is included. The launcher stores the database and first user credential under `data/` in its state directory. For PostgreSQL, give the database user ownership of the application schema and configure the Harness before startup:

```dotenv
JAGENT_DATASOURCE_URL=jdbc:postgresql://db.example.com:5432/jagent
JAGENT_DATASOURCE_USERNAME=jagent
JAGENT_DATASOURCE_PASSWORD=your-database-password
```

The Harness initializes the schema on startup. An empty database creates a new user space. Switching the URL does not transfer existing data. Both databases use the same Runner.

## Run from source

Use `node scripts/start.mjs local` for development. To start the processes manually, export the model settings in the Harness terminal, then run these from the repository root in separate terminals:

1. **Harness** — `mvn -pl examples/server-demo -am -DskipTests package`, then `java -jar examples/server-demo/target/server-demo-2.4.0.jar`. In IntelliJ IDEA, `ServerDemoApplication.main()` starts the same server.
2. **Console** — `npm ci --prefix console`, then `npm --prefix console run dev`.
3. **Runner** — `npm ci --prefix sdk/node`, `npm run build --prefix sdk/node`, then `node sdk/node/dist/cli.js runner`. It reads the local credential and opens the Console. If started manually, select the checkout under **mainbot → Configuration → General & instructions** for source-based help.

## Configuration reference

The launcher reads `.env.local` in `JAGENT_STATE_DIR` (defaults to the checkout or extracted directory). Shell variables take precedence. The standalone npm CLI accepts flags, environment variables and a Harness-issued credential file.

| Variable | Component and default |
| --- | --- |
| `JAGENT_MODEL`, `JAGENT_OPENAI_BASE_URL`, `JAGENT_OPENAI_API_KEY` | Harness model connection |
| `AGENT_BIND_ADDRESS`, `AGENT_SERVER_PORT` | Harness listener, `127.0.0.1:18181` |
| `JAGENT_CONSOLE_HOST`, `JAGENT_CONSOLE_PORT` | Frontend listener, `127.0.0.1:5175` |
| `JAGENT_SERVER_URL` | API origin for Runner and frontend |
| `JAGENT_CONSOLE_URL` | Browser-facing Console address |
| `JAGENT_DATASOURCE_URL`, `JAGENT_DATASOURCE_USERNAME`, `JAGENT_DATASOURCE_PASSWORD` | Harness database. SQLite defaults to `data/agent-server.db` with the launcher or `agent-server.db` with direct JAR startup |
| `JAGENT_LOCAL_TOKEN_FILE` | Harness credential output, `data/user.env` |
| `JAGENT_CONFIG_ROOT` | Harness configuration, `data/server-config` |
| `JAGENT_USER_CREDENTIALS`, `JAGENT_USER_TOKEN` | Runner credential file or token |
| `JAGENT_RUNNER_HOME` | Runner identity and journal root. Defaults to `~/.jagent-runner` for the CLI and `data/coding-runner` for the local stack |
| `JAGENT_RUNNER_NAME`, `JAGENT_RUNNER_DESCRIPTION` | Runner listing in the Console |
| `JAGENT_RUNNER_IDLE_DISCONNECT_TIMEOUT_SECONDS` | Exit after continuous idle disconnection, default 86400 seconds. Zero keeps reconnecting |
| `JAGENT_OPEN_BROWSER` | Set `false` to skip opening the Console after Runner registration |
| `JAGENT_RG_PATH` | Optional absolute path to ripgrep |
| `JAGENT_SOURCE_WORKSPACE` | Source checkout for local mainbot help. Defaults to the checkout or the ZIP's bundled `sources/` |
