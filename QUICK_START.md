# Quick Start

This guide starts the coding-tool example locally. The backend is `examples/coding-tool-app`; the Vue UI is `frontend`.

The HTTP API used by the UI is provided by the optional `jagent-console-spring-boot-starter`. Applications that only call the agent from Java code do not need that starter.

## Requirements

- Java 8
- Maven 3.x
- Node.js `^20.19.0` or `>=22.12.0` and npm

Check your tools:

```bash
java -version
mvn -version
node -v
npm -v
```

## 1. Configure the Model

The example uses a generic OpenAI-compatible Chat Completions provider. It does not select a
provider endpoint or model for you; both values are required when the launcher starts.

Create a local launcher configuration from the repository root.

macOS:

```bash
cp examples/coding-tool-app/launcher/.env.coding.example \
  examples/coding-tool-app/launcher/.env.coding.local
```

Windows Command Prompt:

```bat
copy examples\coding-tool-app\launcher\.env.coding.example examples\coding-tool-app\launcher\.env.coding.local
```

Edit `examples/coding-tool-app/launcher/.env.coding.local` and set at least the provider values
needed by your model:

```dotenv
JAGENT_OPENAI_BASE_URL=http://127.0.0.1:10200/v1
JAGENT_MODEL=your-model-id
JAGENT_OPENAI_API_KEY=
JAGENT_MODEL_STREAM_ENABLED=true
JAGENT_MODEL_INCLUDE_USAGE=true
```

`JAGENT_MODEL` is the identifier accepted by the endpoint and may be a local model path. The API
key is optional; leave it empty for endpoints without Bearer authentication, or set the placeholder
or real token required by the service.

The local file supports normal `.env` comments and quoted values and is ignored by Git. Existing
operating-system environment variables take precedence, so a value can still be overridden for one
launch without editing the file. The launcher reports a configuration error before building when
`JAGENT_OPENAI_BASE_URL` or `JAGENT_MODEL` is missing.

`JAGENT_TEMPERATURE` is optional. If it is not set, the backend does not send `temperature` to the model provider.
Set `JAGENT_MODEL_STREAM_ENABLED=false` when the OpenAI-compatible model endpoint does not support SSE streaming.
Set `JAGENT_MODEL_INCLUDE_USAGE=false` if it rejects the optional
`stream_options.include_usage` field.

For a vision-capable model, the web chat also accepts images. Images are sent to the compatible
endpoint as standard `image_url` content parts with a base64 `data:image/...` URL, so the selected
model and server must support multimodal Chat Completions. Each message accepts up to four images,
10 MB per image, and 20 MB total; `JAGENT_MAX_CHAT_REQUEST_BODY_SIZE` defaults to 32 MB for the
encoded JSON request.

## 2. Start Coding

From the repository root:

macOS:

```bash
./examples/coding-tool-app/launcher/start-coding.command
```

Windows:

```bat
examples\coding-tool-app\launcher\start-coding.cmd
```

The Windows launcher uses `cmd.exe` and Node.js. It does not invoke or require PowerShell.

Both platform wrappers run the same `start-coding.mjs` implementation. The launcher:

- validates the Node.js version, URLs, backend port, Java, JDK, Maven, and npm;
- checks the configured backend port and port `5173` before preparation and again before startup;
- runs `npm ci` only when dependencies are missing or their manifests changed;
- builds the Spring Boot backend with Maven;
- starts Java and the separate Vite development server on `127.0.0.1`;
- waits for both services and opens `http://127.0.0.1:5173`;
- monitors both services and stops the other process tree if either one exits;
- stops active install, build, backend, and frontend processes on Control-C.

Use Control-C in the launcher window to stop coding. Set `JAGENT_OPEN_BROWSER=false` if the browser
should not open automatically.

The backend listens on `http://127.0.0.1:18080`, and Vite listens on `http://127.0.0.1:5173`.

Health check:

```bash
curl http://127.0.0.1:18080/api/health
```

The example uses Spring Boot `spring.datasource.*` configuration. By default it points to
`jdbc:sqlite:jagent-harness.db`. The launcher starts Java from the repository root, so the default
database file remains at `<repository>/jagent-harness.db`.
The JDBC schema is also available in `jagent-store-jdbc` at `db/jagent-harness/schema.sql`.

## 3. Manual Development Mode

The launcher keeps the backend and frontend separate, but developers can still run them in
different terminals for independent restarts and normal Vite output.

Backend, from the repository root:

```bash
mvn -f pom.xml -pl examples/coding-tool-app -am package -DskipTests
java -jar examples/coding-tool-app/target/coding-tool-app-0.8.0.jar
```

Frontend:

In another terminal:

```bash
cd frontend
npm ci
npm run dev:coding
```

Open `http://localhost:5173`.

The Vite dev server proxies `/api` to `http://localhost:18080`.

## 4. Create a Project

In the UI, click `Add new project`, enter a project name, and enter a workspace directory path that the backend process can access.
The name defaults from the final segment of the workspace path, but you can edit it before creating the project.

The example backend validates the path, creates a session, and runs file tools relative to that project directory.

## 5. Useful Configuration

These values can be maintained in `.env.coding.local` when using the launcher:

```dotenv
SERVER_PORT=18080
JAGENT_DATASOURCE_URL=jdbc:sqlite:jagent-harness.db
JAGENT_DATASOURCE_DRIVER=org.sqlite.JDBC
JAGENT_STOP_POLL_INTERVAL_MS=1000
JAGENT_CONFIG_ROOT=~/.jagent-harness
JAGENT_COMPACTION_ENABLED=true
JAGENT_CONTEXT_WINDOW_TOKENS=128000
```

When using the launcher, `JAGENT_API_TARGET` automatically follows `SERVER_PORT`. In manual
development mode, the launcher configuration file is not loaded; export the required variables in
each terminal and set `JAGENT_API_TARGET` explicitly when the backend does not use port `18080`.

## 6. Build and Test

Run backend tests:

```bash
mvn -f pom.xml test
```

Build the frontend:

```bash
cd frontend
npm run build
```
