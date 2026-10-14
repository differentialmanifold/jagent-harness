# Web frontend

The frontend is independent of the Harness and runner. Host this same application on the server machine, on a client machine, or elsewhere. All API requests go to the Harness server; local tools execute in the selected runner.

## Run

From the repository root:

```sh
npm ci --prefix console
export JAGENT_SERVER_URL='https://agent.example.com'
export JAGENT_CLIENT_TOKEN='your-shared-token'
npm --prefix console run dev
```

Open `http://127.0.0.1:5175`. For a local Harness use `http://127.0.0.1:18181`. The Vite proxy forwards `/api` to `JAGENT_SERVER_URL` and adds the token on the frontend host. Model credentials belong only to the Harness. Direct npm commands do not read the repository's `.env.local`; `node scripts/start-frontend.mjs` does.

Add a project and choose an online runner, or select **Server tools only**. For a coding runner, browse or enter a directory on that terminal. New projects enable all registered client tools by default. The directory browser works through the runner’s outbound polling connection. **Configuration → Built-in tools → Client tools** edits the project selection for future runs. Selected tools run with Full access without approval prompts.

## Build and host

```sh
npm run build --prefix console
```

Deploy `console/dist/` to a static web host. Configure that host to forward `/api/` to your Harness server, with SSE buffering disabled and a suitable long request timeout. Keep the frontend and API under the same origin. Supply the server token through the trusted host's proxy rather than putting it into JavaScript assets. The UI uses relative API paths, so no build-time server URL is embedded in the bundle.

For a local preview of the built files:

```sh
export JAGENT_SERVER_URL='https://agent.example.com'
export JAGENT_CLIENT_TOKEN='your-shared-token'
npm --prefix console run preview
```

Vite dev and preview bind to loopback by default. Use `-- --host 0.0.0.0` only when intentionally exposing them on another interface. For deployed access, use an authenticated HTTPS web host; the shared Harness API token is an operator credential.

## Other frontends

A desktop application or another interface can use the same [server APIs](../protocol/README.md). Create a session with a runner ID, submit chat to the runner dispatch endpoint, and display streamed events. Use the normal chat endpoint for sessions with server tools only. Neither frontend hosting nor its programming language changes where tools execute.
