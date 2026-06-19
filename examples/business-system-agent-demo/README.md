# Business System Agent Demo

This example shows how a normal Spring Boot business application embeds JAgentHarness through the SDK.

It intentionally does not depend on `jagent-console-spring-boot-starter`. The host application owns its HTTP API, prompt contribution, tools, and demo skills.

## What It Demonstrates

- Business system prompt extension: `BusinessSystemPromptContributor`
- Business tools: `customer_lookup`, `refund_policy_check`, `ticket_create`
- Source skills: `skills/customer-support/SKILL.md`
- Startup seeding: `DemoSkillSeeder` reads source files under `skills/` and writes them to the JDBC knowledge store
- Database isolation: `harness.store.jdbc.application-id=business-system-agent-demo`

## Run

```bash
export JAGENT_OPENAI_API_KEY=your_api_key
mvn -pl examples/business-system-agent-demo -am spring-boot:run
```

The default HTTP port is `18081`.

```bash
curl -X POST http://localhost:18081/api/business-assistant/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"客户 C-1001 想确认订单 ORD-2026-0619 是否可以退款，需要的话帮他创建跟进工单"}'
```

The application starts by loading `skills/customer-support/SKILL.md` into the database. The agent sees the skill in its prompt and calls the built-in `skill` tool to read it before following the workflow.
