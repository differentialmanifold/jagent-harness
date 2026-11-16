# Personal access and remote deployment

[Documentation](../README.md) · [Quick start](../../QUICK_START.md)

Each user has their own mainbot, Agents, Projects, conversations, memory, Skills and settings. A Harness-issued **User Token** identifies the user. No registration form or interactive login is required. A Runner cannot create a user or join another user's space without a valid credential.

## Local startup

`node scripts/dev.mjs` starts the server and frontend, then the Runner opens your personal Console automatically. The standard server creates a local identity and writes its credential to `data/user.env` on first startup. The local launcher and `RunnerMain` read that file automatically. Keep the database and credential file across restarts.

For separate processes, start the server, frontend, then Runner. In IDEA, use the repository root as the working directory of both Java applications, or set `JAGENT_USER_CREDENTIALS` to the credential file's absolute path.

## Issue a user credential

Run these commands **on the Harness host**, from the same working directory and with the same database configuration as the server. They do not start a web server or Agent execution loops.

```sh
java -jar examples/server-demo/target/server-demo-2.2.1.jar users create --name Alice --output alice.env
java -jar examples/server-demo/target/server-demo-2.2.1.jar users list
```

With the prebuilt application, replace the JAR path with `server/server.jar`. If the server was started by the launcher, first set `JAGENT_DATASOURCE_URL=jdbc:sqlite:data/agent-server.db`; direct Java startup defaults to `jdbc:sqlite:agent-server.db`.

The output file contains `JAGENT_USER_TOKEN`. Its permissions are restricted to the owner on POSIX systems. Deliver it privately to the user; do not commit it, paste it into chat, or distribute it with a public package. The server stores a hash, so it cannot display an existing token again.

## Remote Harness and frontend, local Runner

Host the Console and `/api/` under one public origin. Forward `/api/` to the Harness without injecting a shared Authorization header. Preserve the browser's Cookie, original Host and request scheme; configure trusted reverse-proxy forwarding if TLS terminates at a proxy. Disable SSE buffering. See [Console hosting](../../console/README.md).

On the user's computer:

```sh
java -jar jagent-coding-runner-2.2.1.jar \
  --server http://harness.example.com \
  --credentials /path/to/alice.env
```

The Runner connects outbound and opens the remote Console. If the frontend has a different address from the Harness API origin, add `--console http://console.example.com`; that frontend must proxy `/api/` to the same Harness. Environment equivalents are `JAGENT_SERVER_URL`, `JAGENT_USER_CREDENTIALS` (or `JAGENT_USER_TOKEN`) and `JAGENT_CONSOLE_URL`.

A source checkout can use `node scripts/start-client.mjs` with those variables. Only the Runner runs on the user's computer. Use `--no-browser` or `JAGENT_OPEN_BROWSER=false` for an unattended execution terminal.

The first authorized Runner becomes the user's mainbot environment. Later Runners leave that binding unchanged. Using the same user's token on another machine adds another Runner to the same personal space; different users have different tokens and spaces. Runner state is separated by server and stable user ID. Resetting a token preserves the identity and execution records.

## Browser access

The Runner opens a short-lived, single-use Console link. The frontend removes the ticket from the address and exchanges it for an HttpOnly browser cookie. It never receives the long-lived User Token. A new browser must first be opened by the Runner; knowing the Harness address grants no personal access.

The browser session lasts up to 12 hours and requires its launching Runner to remain online. A normal Runner shutdown locks the page; a crash or network loss is detected by the heartbeat lease (up to 65 seconds). The Console checks access every three seconds. Agents and stored data are retained. Starting the same Runner restores access while the browser session is valid; otherwise restart it to open a fresh link. Use a separate browser profile for a different user on the same Harness.

## Reset or revoke access

```sh
java -jar examples/server-demo/target/server-demo-2.2.1.jar users reset --id usr_example --output replacement.env
java -jar examples/server-demo/target/server-demo-2.2.1.jar users revoke --id usr_example
```

Use the actual ID from `users list`; the automatically created local user's ID is `local`. Resetting preserves data, invalidates the previous token and browser sessions, and re-enables a revoked user. Update every Runner using that token. Revocation prevents further authenticated access; it does not delete the user's data. If `data/user.env` is lost, reset `local` explicitly. Startup never silently replaces an existing credential.

One token grants access to its user's space, including browser access and registering Runners. Anyone holding a copy has those permissions. It is not a restricted execution-only token. Do not copy it to an untrusted machine. Separate per-device token management and account login are future extensions; neither requires changing the stable user IDs or moving user data.

## HTTP and HTTPS

Both are accepted. Remote HTTP produces a Runner warning and a non-blocking Console notice. Authentication still applies, but credentials, messages and tool commands can be intercepted or modified in transit. Use HTTP only with a trusted network or protected tunnel; HTTPS is recommended for remote access. HTTP browser cookies omit `Secure`; HTTPS cookies include it. Both retain HttpOnly, SameSite and cross-site request checks.

## Embedding the server

The standard `server-demo` enables `harness.identity.enabled=true`. An application using the Maven libraries can enable the same setting with the JDBC store and Console starter. `harness.identity.local-token-file` optionally enables the local bootstrap credential file; omit it when issuing every user explicitly. User data uses a namespace derived from the configured application ID and stable user ID. Background work, subscriptions and in-memory execution state follow the same identity. Existing local data remains in the deployment's local namespace.

Custom stores and server tools must enforce the current `UserContext`; custom asynchronous work must capture and restore it. Storage isolation does not sandbox custom server tools or provide operating-system isolation between Runners. Cloud account registration and environment provisioning are not implemented yet.
