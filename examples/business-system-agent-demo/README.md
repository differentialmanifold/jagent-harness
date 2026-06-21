# Business System Agent Demo

This example shows how a normal Spring Boot business application embeds JAgentHarness through the SDK.

It intentionally does not depend on `jagent-console-spring-boot-starter`. The host application owns its HTTP API, prompt contribution, tools, and demo skills.

## What It Demonstrates

- Business system prompt extension: `BusinessSystemPromptContributor`
- Business tools: `product_search`, `inventory_check`, `cart_add`
- Source skills: `src/main/resources/business-demo/skills/shopping-assistant/SKILL.md`
- Startup seeding: `DemoSkillSeeder` reads classpath resources under `business-demo/skills/` and writes them to the JDBC knowledge store
- Database isolation: `harness.store.jdbc.application-id=business-system-agent-demo`

## Run

Set the model API key before starting the Spring Boot process. The local demo endpoint does not require an `Authorization` header, but the backend model provider does.

```bash
export JAGENT_OPENAI_API_KEY=your_api_key
mvn -pl examples/business-system-agent-demo -am spring-boot:run
```

The default HTTP port is `18081`. The chat endpoint streams agent events with Server-Sent Events.

```bash
curl -N -X POST http://localhost:18081/api/business-assistant/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"I need a webcam for remote meetings with a budget under 500. It should be available for same-day delivery. Recommend up to two options and add the best match to my cart."}'
```

The application starts by loading `business-demo/skills/shopping-assistant/SKILL.md` from application resources into the database as `skills/shopping-assistant/SKILL.md`. The agent sees the skill in its prompt and calls the built-in `skill` tool to read it before following the workflow.

The streaming endpoint emits only timeline events that are persisted by the JDBC event store. Transient lifecycle and incremental update events are omitted, and the final answer is available from persisted message and turn events.
