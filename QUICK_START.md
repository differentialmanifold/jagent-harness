# Quick Start

This guide starts the coding-tool example locally. The backend is `examples/coding-tool-app`; the Vue UI is `frontend`.

The HTTP API used by the UI is provided by the optional `jagent-console-spring-boot-starter`. Applications that only call the agent from Java code do not need that starter.

## Requirements

- Java 8
- Maven 3.x
- Node.js and npm

Check your tools:

```bash
java -version
mvn -version
node -v
npm -v
```

## 1. Configure the Model

The example uses an OpenAI-compatible chat completions provider. The default model is `glm-5.2`.

```bash
export JAGENT_OPENAI_API_KEY=your_api_key
```

Optional overrides:

```bash
export JAGENT_OPENAI_BASE_URL=https://open.bigmodel.cn/api/coding/paas/v4
export JAGENT_MODEL=glm-5.2
export JAGENT_MODEL_STREAM_ENABLED=true
```

`JAGENT_TEMPERATURE` is optional. If it is not set, the backend does not send `temperature` to the model provider.
Set `JAGENT_MODEL_STREAM_ENABLED=false` when the OpenAI-compatible model endpoint does not support SSE streaming.

## 2. Start the Backend

From the repository root:

```bash
mvn -f pom.xml -pl examples/coding-tool-app -am package -DskipTests
java -jar examples/coding-tool-app/target/coding-tool-app-0.2.1.jar
```

The backend listens on `http://localhost:8080`.

Health check:

```bash
curl http://localhost:8080/api/health
```

The example uses Spring Boot `spring.datasource.*` configuration. By default it points to
`jdbc:sqlite:jagent-harness.db`, relative to the directory where the backend is started.
The JDBC schema is also available in `jagent-store-jdbc` at `db/jagent-harness/schema.sql`.

## 3. Start the Frontend

In another terminal:

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`.

The Vite dev server proxies `/api` to `http://localhost:8080`.

## 4. Create a Project

In the UI, click `Add new project` and enter a directory path that the backend process can access.

The example backend validates the path, creates a session, and runs file tools relative to that project directory.

## 5. Useful Configuration

```bash
export SERVER_PORT=8080
export JAGENT_DATASOURCE_URL=jdbc:sqlite:jagent-harness.db
export JAGENT_DATASOURCE_DRIVER=org.sqlite.JDBC
export JAGENT_STOP_POLL_INTERVAL_MS=100
export JAGENT_STOP_LEASE_DURATION_MS=10000
export JAGENT_CONFIG_ROOT=~/.jagent-harness
export JAGENT_COMPACTION_ENABLED=true
export JAGENT_CONTEXT_WINDOW_TOKENS=128000
```

If you change `SERVER_PORT`, also update the proxy target in `frontend/vite.config.js`.

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
