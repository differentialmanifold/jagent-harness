# Quick start

[Project overview](README.md) · [Documentation](docs/README.md)

| Where you run it | What to start | Command |
| --- | --- | --- |
| One computer | Harness, web Console and Coding Runner | `node scripts/start.mjs local` |
| Harness host | Standalone Java service | `java -jar jagent-server-2.4.0.jar` |
| Frontend host | Extracted Console and API proxy | `node serve.mjs` |
| Machine with the files or tools | Node.js Coding Runner | `jagent runner --server <url> --credentials <file>` |

## Install and start

For a local source launch, use a JDK (Java 8+), Maven 3.6.3+ and Node.js 22.12+ or 20.19+. Windows users need Git for Windows for the Runner’s `bash` tool. [Release assets](https://github.com/differentialmanifold/jagent-harness/releases)

1. Copy `.env.example` to `.env.local` next to the `scripts` directory. Enter your provider's model ID, OpenAI-compatible base URL and API key if required.

   ```dotenv
   JAGENT_MODEL=your-model-name
   JAGENT_OPENAI_BASE_URL=https://your-provider.example/v1
   JAGENT_OPENAI_API_KEY=your-api-key
   ```

2. Run `node scripts/start.mjs local` from the project root, or double-click the macOS or Windows shortcut listed in the script guide. The launcher builds the Harness and Runner and installs Console dependencies on first use. [Script guide](scripts/README.md)
3. The local launcher opens a signed-in Console. Select **Agents → mainbot → Chat**. Use Ctrl+C to stop this stack. Running the same command again reopens the same local workspace.

## Give mainbot its first job

> Make a release checklist in a new workspace. Write “Run tests” and “Write release notes” to CHECKLIST.md. Mark the first item complete, read it back, and tell me its path and which item is still open.

Open **mainbot → Projects** to see the tool calls and results. Follow up in the same chat with “Mark the release notes complete.” For a longer task, **mainbot → Tasks** shows what it is tracking. [Personal Agent walkthrough](docs/guides/agents.md)

## Use a remote Harness

On the Harness host, export the model variables above and start the release JAR with `java -jar jagent-server-2.4.0.jar`. Java is the only runtime dependency. The default listener is on loopback. To connect from another host, use an SSH tunnel, a reverse proxy, or configure `AGENT_BIND_ADDRESS` for your network.

On the frontend host, extract `jagent-console-2.4.0.zip`, set `JAGENT_SERVER_URL` to the Harness origin reachable from that host, and run `node serve.mjs`. It listens on `127.0.0.1:5175` by default and proxies `/api/`. Alternatively serve `dist/` with your own web server. [Console hosting](console/README.md)

On the execution machine, install the Runner once. Run:

```sh
npm install --global jagent
jagent runner --server https://harness.example.com --credentials /path/to/my-user.env
```

If only SSH is exposed, use `jagent runner --ssh deploy@harness.example.com --credentials /path/to/my-user.env`. The Runner connects to `127.0.0.1:18181` **on the SSH host**. To open the frontend separately, run `jagent open --console <browser-accessible-url>`. [SSH options](sdk/node/README.md#connect-through-ssh)

Use the public Console origin for `--server` if it proxies `/api/`. If the Runner connects directly to the Harness, add `--console <browser-facing-url>`. Have the operator issue the credential on the Harness host. Only the execution machine needs the Runner. [Credentials and remote deployment](docs/guides/access.md)

For offline installation, install the built tarball with `npm install --global /path/to/jagent-0.2.0.tgz`. To produce it from a checkout:

```sh
cd sdk/node
npm ci
npm pack
```

Run `jagent version` to check the installed Runner version. The global `jagent` command starts a Runner by default. Use `jagent open --link` for a fresh browser link, `jagent pair CODE` to approve another device, and `npm uninstall --global jagent` to remove the command. Runner identities and workspaces persist. [Runner guide](sdk/node/README.md)

## Linux servers and existing services

Standalone Runners start headless. Project browser automation is configured by the Harness Skills. Run the Runner service under the OS account that owns the workspace. Use the absolute path from `command -v jagent` and include Node on the service's PATH. Open the Console remotely and approve the pairing code on the Runner machine. [Pairing with a service Runner](docs/guides/access.md#pair-with-a-linux-service-runner)

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

1. **Harness** — `mvn -pl server -am -DskipTests package`, then `java -jar server/target/jagent-server-2.4.0.jar`. After packaging once, run `HarnessApplication.main()` in IntelliJ IDEA to start the server with its generated source resources.
2. **Console** — `npm ci --prefix console`, then `npm --prefix console run dev`.
3. **Runner** — `npm ci --prefix sdk/node`, `npm run build --prefix sdk/node`, then `node sdk/node/dist/cli.js runner`. It reads the local credential. In another terminal, run `node sdk/node/dist/cli.js open` to open the Console. The Coding Runner automatically makes the matching Harness and Console source available to mainbot, including over SSH.

## Configuration reference

The launcher reads `.env.local` in `JAGENT_STATE_DIR` (defaults to the checkout). Shell variables take precedence. The standalone npm CLI accepts flags, environment variables and a Harness-issued credential file.

| Variable | Component and default |
| --- | --- |
| `JAGENT_MODEL`, `JAGENT_OPENAI_BASE_URL`, `JAGENT_OPENAI_API_KEY` | Harness model connection |
| `AGENT_BIND_ADDRESS`, `AGENT_SERVER_PORT` | Harness listener, `127.0.0.1:18181` |
| `JAGENT_CONSOLE_HOST`, `JAGENT_CONSOLE_PORT` | Frontend listener, `127.0.0.1:5175` |
| `JAGENT_SERVER_URL` | API origin for Runner and frontend |
| `JAGENT_SSH`, `JAGENT_SSH_CONFIG` | Optional Runner SSH destination and OpenSSH config file |
| `JAGENT_CONSOLE_URL` | Browser-facing Console address |
| `JAGENT_DATASOURCE_URL`, `JAGENT_DATASOURCE_USERNAME`, `JAGENT_DATASOURCE_PASSWORD` | Harness database. SQLite defaults to `data/agent-server.db` with the launcher or `agent-server.db` with direct JAR startup |
| `JAGENT_LOCAL_TOKEN_FILE` | Harness credential output, `data/user.env` |
| `JAGENT_CONFIG_ROOT` | Harness configuration, `data/server-config` |
| `JAGENT_USER_CREDENTIALS`, `JAGENT_USER_TOKEN` | Runner credential file or token |
| `JAGENT_RUNNER_HOME` | Runner identity and journal root. Defaults to `~/.jagent-runner` for the CLI and `data/coding-runner` for the local stack |
| `JAGENT_RUNNER_NAME`, `JAGENT_RUNNER_DESCRIPTION` | Runner listing in the Console |
| `JAGENT_RUNNER_IDLE_DISCONNECT_TIMEOUT_SECONDS` | Exit after continuous idle disconnection, default 86400 seconds. Zero keeps reconnecting |
| `JAGENT_OPEN_BROWSER` | Set `false` to skip the browser in the all-in-one local launcher |
| `JAGENT_RG_PATH` | Optional absolute path to ripgrep |
