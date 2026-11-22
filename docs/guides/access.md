# Personal access and remote deployment

[Documentation](../README.md) · [Quick start](../../QUICK_START.md)

Each user has their own mainbot, Agents, Projects, conversations, memory, Skills and settings. A Harness-issued **User Token** identifies the user. No registration form or interactive login is required. A Runner cannot create a user or join another user's space without a valid credential.

## Local startup

`node scripts/dev.mjs` starts the server and frontend, then the Runner opens your personal Console automatically. The standard server creates a local identity and writes its credential to `data/user.env` on first startup. The local launcher and `RunnerMain` read that file automatically. Keep the database and credential file across restarts.

For separate processes, start the server, frontend, then Runner. In IDEA, use the repository root as the working directory of both Java applications, or set `JAGENT_USER_CREDENTIALS` to the credential file's absolute path.

## Issue a user credential

Run these commands **on the Harness host**, from the same working directory and with the same database configuration as the server. They do not start a web server or Agent execution loops.

```sh
java -jar examples/server-demo/target/server-demo-2.4.0.jar users create --name Alice --output alice.env
java -jar examples/server-demo/target/server-demo-2.4.0.jar users list
```

With the prebuilt application, replace the JAR path with `server/server.jar`. For SQLite, point `JAGENT_DATASOURCE_URL` at the same database file as the running Harness. For PostgreSQL, export the same JDBC URL, username, password and any custom application ID in this shell. Direct JAR commands read the process environment. [Database settings](../../QUICK_START.md#choose-a-database)

The output file contains `JAGENT_USER_TOKEN`. Its permissions are restricted to the owner on POSIX systems. Deliver it privately to the user; do not commit it, paste it into chat, or distribute it with a public package. The server stores a hash, so it cannot display an existing token again.

## Remote Harness and frontend, local Runner

Host the Console and `/api/` under one public origin. Forward `/api/` to the Harness without injecting a shared Authorization header. Preserve the browser's Cookie, original Host and request scheme; configure trusted reverse-proxy forwarding if TLS terminates at a proxy. Disable SSE buffering. See [Console hosting](../../console/README.md).

On the user's computer:

```sh
java -jar jagent-coding-runner-2.4.0.jar \
  --server http://harness.example.com \
  --credentials /path/to/alice.env
```

The Runner connects outbound and opens the remote Console. If the frontend has a different address from the Harness API origin, add `--console http://console.example.com`; that frontend must proxy `/api/` to the same Harness. Environment equivalents are `JAGENT_SERVER_URL`, `JAGENT_USER_CREDENTIALS` (or `JAGENT_USER_TOKEN`) and `JAGENT_CONSOLE_URL`.

A source checkout can use `node scripts/start-client.mjs` with those variables. Only the Runner runs on the user's computer. Use `--no-browser` or `JAGENT_OPEN_BROWSER=false` for an unattended execution terminal.

The first authorized Runner becomes the user's mainbot environment. Later Runners leave that binding unchanged. Using the same user's token on another machine adds another Runner to the same personal space; different users have different tokens and spaces. Runner state is separated by server and stable user ID. Resetting a token preserves the identity and execution records.

## Access from another browser or device

To switch browsers on the same computer, use **System Settings → Devices → Add device → Copy link** in the signed-in browser, then paste the new link into the other browser within 60 seconds. A loopback Console address works for this. The address bar of an already signed-in page is not a sign-in link. Keep using the hostname in the new link: `localhost` and `127.0.0.1` have separate cookies.

If you have access to the Runner machine, you can sign in even after every browser session expires:

1. Visit the Console in the desired browser and click **Pair with a Runner**. It displays a command with a five-minute code.
2. On the machine running your authenticated Runner, execute that command, for example `jagent pair K7MD-P4TX`. Check the browser, code, Harness and user in the terminal, then type `yes`.
3. Return to the original browser. It signs in automatically and opens the workspace when mainbot is online.

To open another browser on the same machine, `jagent open --link` prints a single-use link valid for 60 seconds. `jagent open` opens the default browser. Both commands reuse the existing Runner identity. For a standalone JAR, use `java -jar runner.jar open --link` or `java -jar runner.jar pair CODE`.

Each successful Coding Runner registration records the Harness address, Console address, state directory, user ID and credential file path in a local profile. If multiple profiles match, the command lists their IDs; repeat it with `--profile <id>`. When the Runner used only `JAGENT_USER_TOKEN`, supply the same environment variable in the command's shell. A profile does not store that token. The browser making the pairing request holds the private claim in memory; refreshing the page requires a new code.

For another computer or phone, open **System Settings → Devices → Add device** on an authorized browser. Scan the QR code with your phone or open the copied link on the other computer. The link expires after 60 seconds and can be used once. Each browser gets its own HttpOnly session cookie; it never receives the long-lived User Token. The new device needs no Runner. Use a Console network address reachable from that device, rather than `localhost`.

Each browser sign-in expires automatically 30 days after authorization. In **System Settings → Devices**, **This browser** marks your current session. Other rows show **Browser** and a short session identifier. The timestamp is the automatic expiry in your local time zone. **Revoke access** ends another session immediately, and **Sign out** ends the current one. Pair again or use a fresh link after sign-out, revocation or expiry. Use a separate browser profile for a different Harness user.

The server requires **that user's mainbot Runner to be online** before opening business pages or accepting browser business API requests. All browsers share this availability check, regardless of which Runner originally opened them. When mainbot's Runner disconnects, the Console displays **Waiting for mainbot** and keeps the sign-in. The page restores automatically when that Runner reconnects. Another Runner being online does not unlock it. Pausing an Agent does not lock the page, so you can still resume it.

A normal Runner shutdown reports its disconnection. A crash or network loss is detected by the heartbeat lease (up to 65 seconds); the Console checks access every three seconds. Credentials, browser authorization and stored data remain intact. Runner/API credentials can still reconnect and manage execution while the browser is waiting.

### Pair with a Linux service Runner

Run `jagent pair` in an interactive terminal as the Runner service’s OS user, with its `JAGENT_STATE_DIR` if customized. Profiles belong to that user. If the service was given its token through the environment, supply it in the pairing context too. A new root or SSH shell can have a different home, environment and PATH.

If there is no discoverable profile, an authorized operator with access to the existing Runner files can specify its connection explicitly. Use the code from your browser and the values from your service configuration:

```sh
jagent pair ABCD-2345 --server http://127.0.0.1:18181 \
  --credentials /srv/jagent/runner-user.env --home /srv/jagent/runner-state
```

Use the **same API origin** the Runner registered against and the **same state root** it used with `--home`. The SDK locates the existing identity under that root by Harness origin and user. A public HTTPS address and a loopback HTTP address produce different identities even when they reach the same Harness. For `open --link`, `--console` controls the browser-facing address separately. Pairing reuses the existing identity while the Runner continues serving.

### First access without a desktop Runner

An operator can generate a link on the Harness host, using the same database configuration as the running server:

```sh
java -jar examples/server-demo/target/server-demo-2.4.0.jar users link \
  --id usr_example --console https://harness.example.com --output console-link.txt
```

Use the user ID from `users list` (`local` for the automatically created local user). With the prebuilt package, use `server/server.jar`. The private output file contains a single-use link valid for 60 seconds. Open it promptly on the intended browser. This does not reset the User Token or require a Runner on the browser's device. The user's mainbot Runner must still be connected to use the workspace.

## Runner idle disconnection

A running Runner exits normally after **24 consecutive hours both idle and disconnected from the Harness**. Retry delays increase up to 60 seconds with jitter. A successful poll or tool activity resets the idle-disconnection timer. Idle Runners stay running while the Harness is reachable; active work is not interrupted by this timeout. Authentication failures still end the connection immediately.

Set `JAGENT_RUNNER_IDLE_DISCONNECT_TIMEOUT_SECONDS` or `--idle-disconnect-timeout <seconds>` to change the default `86400`; `0` keeps reconnecting indefinitely. The timeout applies to the running receiver; initial credential validation still needs a reachable Harness.

Pending results are saved before exit. Restart with the same state directory and use the existing recovery workflow for interrupted work; do not replay a tool merely because delivery failed. Independent background processes launched by tools are not automatically removed. A stopped Runner requires local startup, a service manager, or SSH from another terminal. If a process supervisor is used, configure it to respect a normal exit rather than immediately restarting it.

## Reset or revoke access

```sh
java -jar examples/server-demo/target/server-demo-2.4.0.jar users reset --id usr_example --output replacement.env
java -jar examples/server-demo/target/server-demo-2.4.0.jar users revoke --id usr_example
```

Use the actual ID from `users list`; the automatically created local user's ID is `local`. Resetting preserves data, invalidates the previous token and browser sessions, and re-enables a revoked user. Update every Runner using that token. Revocation prevents further authenticated access; it does not delete the user's data. If `data/user.env` is lost, reset `local` explicitly. Startup never silently replaces an existing credential.

One token grants access to its user's space, including browser access and registering Runners. Anyone holding a copy has those permissions. It is not a restricted execution-only token. Do not copy it to an untrusted machine. Separate per-device token management and account login are future extensions; neither requires changing the stable user IDs or moving user data.

## HTTP and HTTPS

Both are accepted. Remote HTTP produces a Runner warning and a non-blocking Console notice. Authentication still applies, but credentials, messages and tool commands can be intercepted or modified in transit. Use HTTP only with a trusted network or protected tunnel; HTTPS is recommended for remote access. HTTP browser cookies omit `Secure`; HTTPS cookies include it. Both retain HttpOnly, SameSite and cross-site request checks.

## Embedding the server

The standard `server-demo` enables `harness.identity.enabled=true`. An application using the Maven libraries can enable the same setting with the JDBC store and Console starter. `harness.identity.local-token-file` optionally enables the local bootstrap credential file; omit it when issuing every user explicitly. User data uses a namespace derived from the configured application ID and stable user ID. Background work, subscriptions and in-memory execution state follow the same identity. Existing local data remains in the deployment's local namespace.

Custom stores and server tools must enforce the current `UserContext`; custom asynchronous work must capture and restore it. Storage isolation does not sandbox custom server tools or provide operating-system isolation between Runners. Cloud account registration and environment provisioning are not implemented yet.
