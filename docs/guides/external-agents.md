# Use JAgent from another Agent

[Documentation](../README.md) · [Personal Agents](agents.md)

Let an external assistant delegate a goal to JAgent without operating the Console. The bundled Skill uses HTTP and a dependency-free Node.js client; no MCP setup is required.

## Set up

1. Start the JAgent server and, for local execution, a Runner on the intended machine. Use [Quick start](../../QUICK_START.md) if they are not running yet.
2. Copy the complete [`skills/jagent/`](../../skills/jagent) directory into the external assistant's Skill directory. Keep `SKILL.md`, `scripts/` and `references/` together.
3. Make Node.js 22+ available to that assistant. Set `JAGENT_SERVER_URL` to the Harness address and `JAGENT_CLIENT_TOKEN` if required. The local URL defaults to `http://127.0.0.1:18181`.
4. Ask the external assistant to use the JAgent Skill. For example:

> Use JAgent to build and test a tiny Java TODO program. Use my coding Runner and create a suitable Agent if one does not exist. Follow its progress and report the verified result.

The external assistant can discover Runners, find or create an Agent, open a conversation, submit a goal, and read task progress and results. No Agent needs to be created manually in the UI. The same conversation is visible under **Agents → Chat**.

## Follow the work

An accepted message is not a completed goal. A decision turn may finish while Projects or scheduled checks are still running. The Skill explains how to inspect tasks, evidence and incremental history, and how to continue in the same conversation.

The client does not automatically replay writes after connection errors. For uncertain Agent or conversation creation, inspect existing records before creating another. For a failed send, retain its message ID when checking delivery or retrying.

Read the [Skill instructions](../../skills/jagent/SKILL.md) for the assistant workflow and the [HTTP/SDK reference](../../skills/jagent/references/api.md) for commands and direct JavaScript use. Those files are self-contained so the installed Skill works outside this repository.
