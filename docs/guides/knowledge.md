# Memory and learning

[Documentation](../README.md) · [Personal Agents](agents.md)

An Agent keeps knowledge across conversations. Use memory for stable preferences and facts, Skills for reusable procedures, and tasks for unfinished work.

## Try it

1. In Agent Chat, say: “Remember that I prefer small Java examples without dependencies.” Open **Knowledge → Memory** to inspect what was saved.
2. Complete and verify a task, then say: “Save the verified way you built and tested this example as a reusable Skill.” Open **Knowledge → Skills & learning** to read the method.
3. Start another conversation and ask for a related task. Inspect the tool trace to see whether the Agent loads the Skill; listing a Skill does not mean it has been read.

No introductory Skill is required to enable planning or tools. Private Skills add domain methods and verified lessons; they are not automatically published to project or global scopes.

## Review and correct knowledge

| View | Use it to… |
| --- | --- |
| Knowledge → Memory | Read and edit persistent preferences and facts |
| Knowledge → Skills & learning | Browse private Skills and their supporting resources |
| Learning history → View change | Compare edits, follow source conversations, or undo the latest edit to a resource |
| Search history | Find a phrase in this Agent's own conversations and projects and read nearby evidence |
| Review this conversation | Request a knowledge review of the conversation |

Memory and Skill edits record their previous content, reason and source conversation. Undo is recorded too. Reload before saving if another edit changed the revision. Memory is limited to 24,000 characters and Skill files to 40,000 each; oversized edits are rejected instead of silently truncated.

History search matches literal phrases, including Chinese substrings, without an embedding service. It is useful when the relevant evidence is in another conversation or no longer in active model context. Other Agents' private history is excluded.

## What happens automatically

The Agent is instructed to save verified lessons after corrections and successful work. A bounded background review supplements this after project-result turns or tool-heavy turns, unless knowledge was already edited. Context compaction can also request a review of messages leaving active context; the full database history remains available.

A review makes one model call and may apply one grounded knowledge edit. It cannot run project tools or send user messages. Reviews yield to foreground work and can finish with no change. Inspect review status for errors; only actual edits appear in learning history. A failed review does not fail the original task.

The server host can configure automatic learning using the [Personal Agent settings](../../jagent-harness-sdk/README.md#personal-agent-settings). Foreground memory and Skill editing remain available when automatic learning is disabled.
