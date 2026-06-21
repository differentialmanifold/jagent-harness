# Business System Agent Console Demo

This example shows the same embedded business-system agent logic as `business-system-agent-demo`, but exposes it through `jagent-console-spring-boot-starter` so it can be used from the shared Vue console UI.

The host application owns the business prompt, tools, and demo skills. The console starter only supplies the HTTP API used by the UI.

## What It Demonstrates

- Console API: `/api/chat`, `/api/sessions`, `/api/agent/context`, `/api/vfs/files`
- Business system prompt extension: `BusinessSystemPromptContributor`
- Business tools: `product_search`, `inventory_check`, `cart_add`
- Source skills: `src/main/resources/business-console-demo/skills/shopping-assistant/SKILL.md`
- Startup seeding: `DemoSkillSeeder` reads classpath resources under `business-console-demo/skills/` and writes them to the JDBC knowledge store
- Database isolation: the demo can share the same physical database as the coding app; rows are separated by `harness.store.jdbc.application-id=business-system-agent-console-demo`

## Run

Set the model API key before starting the Spring Boot process.

```bash
export JAGENT_OPENAI_API_KEY=your_api_key
mvn -pl examples/business-system-agent-console-demo -am spring-boot:run
```

The backend listens on `http://localhost:18082`.

Start the Vue console UI in another terminal and point it at this backend:

```bash
cd frontend
npm run dev:business
```

Open `http://localhost:5174`.

The business console mode keeps the same project and chat interaction model as the coding console. When you add a project, enter a project name only; there is no workspace path because the shopping tools do not read or write workspace files.

The shared production build and preview scripts are kept as the coding-console defaults: `npm run build` and `npm run preview`.

Try this prompt:

```text
I need a webcam for remote meetings with a budget under 500. It should be available today. Recommend up to two options and add the best match to my cart.
```

The application starts by loading `business-console-demo/skills/shopping-assistant/SKILL.md` from application resources into the database as `skills/shopping-assistant/SKILL.md`. The agent sees the skill in its prompt and calls the built-in `skill` tool to read it before following the workflow.

## Useful Configuration

```bash
export SERVER_PORT=18082
export JAGENT_DATASOURCE_URL=jdbc:sqlite:jagent-harness.db
export JAGENT_APPLICATION_ID=business-system-agent-console-demo
export JAGENT_BUSINESS_CORS_ORIGIN=http://localhost:5174
export JAGENT_MODEL=glm-5.2
export JAGENT_MODEL_STREAM_ENABLED=true
```
