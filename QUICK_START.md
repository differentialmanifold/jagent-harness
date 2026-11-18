# Quick start

[Project overview](README.md) · [Documentation](docs/README.md)

Start locally and give **mainbot** one small job. The full application runs the Harness, web Console and Coding Runner on your computer. The Harness creates your personal mainbot and its first chat. The first authenticated Runner binds to mainbot and provides its execution environment.

## Install and start

1. **Get the full application.** Use an extracted `jagent-<version>.zip` with Java 8+ and Node.js 22.12+ on your PATH. The same installer works from a Git checkout with a JDK 8+, Maven 3.6.3+ and Node.js 22.12+. SQLite is included. [Release packages](https://github.com/differentialmanifold/jagent-harness/releases) · [Source checkout](#run-from-source)
2. **Install the command.** From the extracted directory or repository root, run `sh scripts/install.sh` on macOS/Linux or `scripts\install.cmd` on Windows. Open a new terminal after installation.
3. **Configure the model.** Edit `~/.jagent/.env.local` (Windows: `%USERPROFILE%\.jagent\.env.local`). Set the model ID and the base URL supplied by your OpenAI-compatible provider. Set the key if your provider requires one.

   ```dotenv
   JAGENT_MODEL=your-model-name
   JAGENT_OPENAI_BASE_URL=https://your-provider.example/v1
   JAGENT_OPENAI_API_KEY=your-api-key
   ```

4. **Run `jagent`.** Keep the terminal open. The Runner opens a signed-in Console at `http://127.0.0.1:5175`. Select **Agents → mainbot → Chat**.

## Give mainbot its first job

> Make a release checklist in a new workspace. Write two items to CHECKLIST.md, “Run tests” and “Write release notes”. Mark “Run tests” complete, read the file back, and tell me its path and what is still open.

In **mainbot → Projects**, open the Project conversation to see the tools writing and reading the file on your Runner. In **mainbot → Chat**, look for the path and the open item, “Write release notes”. Follow up with “Mark the release notes complete and check the file again.” The update should refer to the same file.

For a larger goal, open **mainbot → Tasks** to see the steps it tracks, what it is waiting for, and the results it has reviewed. The [personal Agent walkthrough](docs/guides/agents.md) includes a longer example. To direct the tools yourself, open **Projects** and choose your own workspace. [Project guide](docs/guides/projects.md)

## Return to your workspace

| Situation | What to do |
| --- | --- |
| Open the Console again | On the Runner machine, run `jagent open`, or `jagent open --link` to get a one-time link for another browser. |
| Sign in from a phone or another computer | Visit the Console at a reachable address, select **Pair with a Runner**, then run `jagent pair CODE` **on the Runner machine** and confirm. |
| The Console says **Waiting for mainbot** | Bring the Runner bound to your mainbot back online. The signed-in page opens automatically when it reconnects. |
| Stop and resume a local installation | Press **Ctrl+C** in the launch terminal. Run `jagent` again to reopen the same personal space. |

The installer copies the application and sets up the launcher for the installing OS user under `~/.local/share/jagent` (Windows: `%LOCALAPPDATA%\JAgent`). The full package creates the model configuration on first install and keeps local Harness data in `~/.jagent`. To uninstall, stop the processes and run `sh scripts/uninstall.sh` or `scripts\uninstall.cmd` from the checkout or extracted ZIP. Personal data is retained. Use the same `JAGENT_INSTALL_DIR` if you chose a custom installation directory.

## Linux servers and existing services

Install under the account that will run JAgent. The **full ZIP** provides all commands. The **Coding Runner offline ZIP** provides `runner`, `open` and `pair` and requires only Java. If the Harness and Runner already run as services, installing the Runner ZIP gives that user the CLI; installation itself starts no processes. Use `jagent --help` to see the installed commands. For the package version, check `distribution.json` in the full ZIP or `coding/manifest.json` in the Runner ZIP.

```sh
sh scripts/install.sh
# Works immediately, including over SSH with the default install directory:
"$HOME/.local/share/jagent/bin/jagent" --help
command -v java
```

Interactive Bash and Zsh sessions can use `jagent` on PATH after opening a new terminal. SSH commands and systemd units may skip shell startup files. For a unit, use the **absolute launcher path** in `ExecStart` and set its `PATH` to include Java (and Node.js for the full server). With a custom `JAGENT_INSTALL_DIR`, the launcher is under its `bin/` directory. For an all-in-one Linux host without a desktop, set `JAGENT_OPEN_BROWSER=false` in the full installation’s `.env.local` and run `jagent`. From your laptop, forward the Console with `ssh -L 5175:127.0.0.1:5175 user@linux-host`, visit `http://127.0.0.1:5175`, then approve the browser’s pairing code in an interactive shell on the Linux host. For a standalone Runner, pass `--no-browser`. [Pairing with a service Runner](docs/guides/access.md#pair-with-a-linux-service-runner)

## Choose a database

SQLite is the default for `jagent`, `jagent server` and the source launchers. For PostgreSQL, provision a database and a login role that owns its application schema and can create tables and indexes. Set the following **on the Harness host**, in `~/.jagent/.env.local` for the installed application, the checkout’s `.env.local` for source launchers, or the service environment for direct JAR startup:

```dotenv
JAGENT_DATASOURCE_URL=jdbc:postgresql://db.example.com:5432/jagent
JAGENT_DATASOURCE_USERNAME=jagent
JAGENT_DATASOURCE_PASSWORD=your-database-password
```

Start the server normally. Both drivers are bundled and the Harness initializes the tables for the selected database. Select the database before first startup. Switching an existing installation to an empty database starts a new user space; it does not migrate conversations or credentials. For a fresh global instance, set `JAGENT_STATE_DIR` in the launching shell to a new directory, place the model and database settings in its `.env.local`, and let the server issue its matching `data/user.env`.

## Use a remote Harness

Run `jagent server` on the host with the model configuration. Publish the Console and its `/api/` proxy at an address the execution machine can reach. Have the Harness operator issue a personal credential file. On the machine with the files and tools, install the Coding Runner offline ZIP using the same installer (Java 8+ is sufficient), then run:

```sh
jagent runner --server https://harness.example.com --credentials /path/to/my-user.env
```

The Runner connects outbound. If the browser-facing Console has a different address, add `--console <url>`. Keep mainbot’s Runner online to access its workspace from any authorized browser. See [personal access and remote deployment](docs/guides/access.md) for credentials, HTTP/HTTPS, reverse-proxy settings and pairing.

## Run from source

Install Git, JDK 8+, Maven 3.6.3+ and Node.js 22.12+ (Node 20.19 is also supported). To run directly from the checkout:

```sh
git clone https://github.com/differentialmanifold/jagent-harness.git
cd jagent-harness
cp .env.example .env.local
```

Set the model connection in the checkout's `.env.local` as above, then run `node scripts/dev.mjs`. The Runner opens the same mainbot experience and registers this checkout for source-based help. This launcher keeps its database and user credential under the checkout’s `data/` directory. An extracted full application also supports this portable launcher with its bundled source.

<details>
<summary>Start components separately or with desktop shortcuts</summary>

Run each component in its own terminal. Start the Runner after the Harness and frontend are ready.

| Component | Command | Windows shortcut | macOS shortcut |
| --- | --- | --- | --- |
| All three | `node scripts/dev.mjs` | `scripts/dev.cmd` | `scripts/dev.command` |
| Harness | `node scripts/start-server.mjs` | `scripts/start-server.cmd` | `scripts/start-server.command` |
| Web Console | `node scripts/start-frontend.mjs` | `scripts/start-frontend.cmd` | `scripts/start-frontend.command` |
| Coding Runner | `node scripts/start-client.mjs --mainbot` | `scripts/start-client.cmd` | `scripts/start-client.command` |

The Java launchers build the needed modules. Pass `--skip-build` to reuse existing JARs. `--mainbot` on the client launcher also registers the checkout for mainbot’s source help.

</details>

<details>
<summary>Start manually without launchers, or run the Java processes in IntelliJ IDEA</summary>

From the repository root, build once:

```sh
mvn -DskipTests package
npm ci --prefix console
```

Use three terminals with the repository root as the working directory. Direct Java startup reads the model settings from the environment.

**1. Harness** — set the provider values and start the server. Wait until it reports that it has started.

```sh
export JAGENT_MODEL='your-model-name'
export JAGENT_OPENAI_BASE_URL='https://your-provider.example/v1'
export JAGENT_OPENAI_API_KEY='your-api-key'
java -jar examples/server-demo/target/server-demo-2.3.1.jar
```

**2. Web Console**

```sh
npm --prefix console run dev
```

**3. Coding Runner** — it reads the server-generated `data/user.env` and opens your browser.

```sh
java -jar examples/coding-runner/target/jagent-coding-runner-2.3.1.jar
```

In IntelliJ IDEA, import the root `pom.xml` and use the repository root as the working directory. Run `ServerDemoApplication.main()` with the model environment variables, start the web Console as above, then run `RunnerMain.main()` with no arguments. For source-based help with a manual launch, select the checkout in **mainbot → Configuration → General & instructions**.

</details>

## Configuration reference

<details>
<summary>Model, addresses, storage and Runner settings</summary>

| Variable | Purpose / default |
| --- | --- |
| `JAGENT_MODEL` | Model name; required on the server |
| `JAGENT_OPENAI_BASE_URL` | OpenAI-compatible API endpoint; required on the server |
| `JAGENT_OPENAI_API_KEY` | Model API key, if required by the provider; server only |
| `AGENT_BIND_ADDRESS` | Server bind address; `127.0.0.1` for local startup |
| `JAGENT_DATASOURCE_URL` | JDBC URL for SQLite or PostgreSQL. SQLite defaults: `jdbc:sqlite:agent-server.db` directly, `jdbc:sqlite:data/agent-server.db` with launchers |
| `JAGENT_DATASOURCE_USERNAME`, `JAGENT_DATASOURCE_PASSWORD` | PostgreSQL login. Leave unset for default SQLite. |
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
| `JAGENT_RUNNER_IDLE_DISCONNECT_TIMEOUT_SECONDS` | Exit after consecutive idle disconnection; `86400` (24 hours), `0` keeps reconnecting |
| `JAGENT_RUNNER_NAME` | Display name; machine hostname for direct startup, `coding-local` in launchers |
| `JAGENT_RG_PATH` | Optional path to ripgrep; search tools also have a Java fallback |

The standalone runner defaults to `~/.jagent-runner` unless `--home` or `JAGENT_RUNNER_HOME` is provided. One identity represents one terminal and can serve multiple project directories. The SDK keeps a separate identity and journal for each server and user under that home; do not copy an identity to another terminal. See [Runner state](examples/coding-runner/README.md#state-cancellation-and-recovery).

</details>
