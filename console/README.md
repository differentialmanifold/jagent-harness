# Web frontend

[Documentation](../docs/README.md) · [Quick start](../QUICK_START.md)

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

For using the UI, see [Projects](../docs/guides/projects.md), [Personal Agents](../docs/guides/agents.md), and [Configuration](../docs/guides/configuration.md).

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

A desktop application or another interface can use the same [server APIs](../protocol/README.md). Create a session for the project and send messages to `POST /api/v1/chat`. The server selects execution from the project configuration; the frontend displays the streamed events. `/api/v1/runners/chat` is reserved for authenticated Runners advancing claimed work. Neither frontend hosting nor its programming language changes where tools execute.
