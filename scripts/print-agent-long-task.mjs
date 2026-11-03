#!/usr/bin/env node
// Print the Agent long-task verification prompts and scorecard.
// Stdout only: no network, no model calls, no environment reads.

const stages = [
  [
    'Stage 1 — tiny TODO',
    'Build a tiny Java TODO program that can add a task, list tasks, and mark a task complete. Keep tasks in memory; no UI or dependencies. Run a small test covering those three operations, fix any failures, and tell me where the files are and whether the test passed.',
  ],
  [
    'Stage 2 — delete follow-up',
    'Add the ability to delete a task. Update and run the test.',
  ],
  [
    'Stage 3 — save, load, and keyword search',
    'Extend this TODO program with saving and loading a file, plus keyword search. Keep it dependency-free. Existing behavior must keep working. Invalid files must produce a clear error without destroying the current tasks. Deliver the code and reproducible tests, and resolve any failures you find.',
  ],
]

const recoveryPrompt =
  'The tests are failing. Inspect the project, fix the failure, keep the existing TODO behavior, and re-run the tests until they pass.'

const scheduledPrompt =
  "In two minutes, check this project's saved-file tests again and tell me only if there is a problem."

const checks = [
  'Tasks created with dependencies and acceptance',
  'Project sessions used',
  'Inspect-before-edit evidence',
  'Tests passing',
  'Recovery after a forced failure',
  'Scheduled check (optional)',
]

const args = process.argv.slice(2)
if (args.includes('--help') || args.includes('-h')) {
  console.log(
    'Print the staged prompts and scorecard for docs/agent-long-task-verification.md.\nUsage: node scripts/print-agent-long-task.mjs',
  )
  process.exit(0)
}
if (args.length > 0) {
  console.error('Unknown arguments. Usage: node scripts/print-agent-long-task.mjs')
  process.exit(1)
}

const lines = [
  'Agent long-task verification',
  'Playbook: docs/agent-long-task-verification.md',
  'Guide: examples/personal-agents/README.md (sections 2–4)',
  '',
  'This command prints text only. It does not start services or call a model.',
  'Startup: node scripts/dev.mjs',
  'Agent: Agents → New Agent, name Developer, runner coding-local. Console: http://127.0.0.1:5175',
  '',
]
for (const [title, prompt] of stages) {
  lines.push(title, prompt, '')
}
lines.push(
  'Forced failure',
  'While stage 3 is in progress, insert this as the first statement of the test method:',
  'throw new RuntimeException("forced verification failure");',
  'If that session already finished green, insert the line again and send:',
  recoveryPrompt,
  '',
  'Optional scheduled check',
  scheduledPrompt,
  '',
  'Scorecard',
)
for (const check of checks) lines.push(`[ ] ${check}`)
console.log(lines.join('\n'))
