# Use JAgent from another Agent

[Documentation](../README.md) · [Personal Agents](agents.md)

Let an external assistant delegate a goal to JAgent without operating the Console. The bundled Skill uses HTTP and a dependency-free Node.js client; no MCP setup is required.

## Set up

1. Start the JAgent server and, for local execution, a Runner on the intended machine. Use [Quick start](../../QUICK_START.md) if they are not running yet.
2. Copy the complete [`skills/jagent/`](../../skills/jagent) directory into the external assistant's Skill directory. Keep `SKILL.md`, `scripts/` and `references/` together.
3. Make Node.js 20 (20.19 or later) or Node.js 22.12+ available to that assistant. Set `JAGENT_SERVER_URL` to the Harness address and export the intended user’s Harness-issued `JAGENT_USER_TOKEN` in the assistant’s environment. The local URL defaults to `http://127.0.0.1:18181`. [Personal access](access.md)
4. Ask the external assistant to use the JAgent Skill. For example:

> Use JAgent to build and test a tiny Java TODO program. Ask mainbot to use my coding Runner. Follow its progress and report the verified result.

The Skill connects directly to your mainbot. You can use its first chat or start a new one. For work on a particular Runner or with a specialist, include that requirement in the message. mainbot handles Agent discovery, creation and delegation, and reports back in the same chat. You can follow the conversation under **Agents → mainbot → Chat**.

## Follow the work

An accepted message is not a completed goal. A decision turn may finish while Projects or scheduled checks are still running. The Skill explains how to inspect tasks, evidence and incremental history, and how to continue in the same conversation.

The client does not automatically replay writes after connection errors. If chat creation is uncertain, list mainbot’s conversations before trying again. For a failed send, retain its message ID when checking delivery or retrying.

Read the [Skill instructions](../../skills/jagent/SKILL.md) for the assistant workflow and the [HTTP/SDK reference](../../skills/jagent/references/api.md) for commands and direct JavaScript use. Those files are self-contained so the installed Skill works outside this repository.
