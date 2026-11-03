import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const scriptPath = resolve(root, 'scripts/print-agent-long-task.mjs')
const playbookPath = resolve(root, 'docs/agent-long-task-verification.md')
const guidePath = resolve(root, 'examples/personal-agents/README.md')

function officialPrompts() {
  const guide = readFileSync(guidePath, 'utf8')
  const quotes = [...guide.matchAll(/^> (.+)$/gm)].map(match => match[1])
  assert.equal(quotes.length, 3)
  const scheduledLine = guide.split('\n').find(line => line.includes('two minutes'))
  const scheduled = scheduledLine.slice(scheduledLine.indexOf('“') + 1, scheduledLine.indexOf('”'))
  assert.match(scheduled, /^In two minutes,/)
  return { quotes, scheduled }
}

test('printer emits the guide prompts and scorecard without a model call', () => {
  const { quotes, scheduled } = officialPrompts()
  const source = readFileSync(scriptPath, 'utf8')
  assert.match(source, /^#!\/usr\/bin\/env node\n/)
  assert.doesNotMatch(source, /^import /m)
  assert.doesNotMatch(source, /process\.env/)
  assert.doesNotMatch(source, /\bfetch\s*\(/)
  assert.doesNotMatch(source, /child_process/)
  assert.doesNotMatch(source, /from ['"]node:/)

  const started = Date.now()
  const result = spawnSync(process.execPath, [scriptPath], {
    cwd: root,
    encoding: 'utf8',
    timeout: 5000,
    env: { PATH: process.env.PATH },
  })
  assert.equal(result.status, 0)
  assert.equal(result.stderr, '')
  assert.ok(Date.now() - started < 5000)
  for (const quote of quotes) assert.ok(result.stdout.includes(quote))
  assert.ok(result.stdout.includes(scheduled))
  for (const check of [
    'Tasks created with dependencies and acceptance',
    'Project sessions used',
    'Inspect-before-edit evidence',
    'Tests passing',
    'Recovery after a forced failure',
    'Scheduled check (optional)',
  ]) {
    assert.ok(result.stdout.includes(`[ ] ${check}`))
  }

  const help = spawnSync(process.execPath, [scriptPath, '--help'], { cwd: root, encoding: 'utf8', timeout: 5000 })
  assert.equal(help.status, 0)
  assert.match(help.stdout, /docs\/agent-long-task-verification\.md/)
  const unknown = spawnSync(process.execPath, [scriptPath, '--run'], { cwd: root, encoding: 'utf8', timeout: 5000 })
  assert.equal(unknown.status, 1)
  assert.equal(unknown.stdout, '')
})

test('playbook quotes the official prompts and its links resolve', () => {
  const { quotes, scheduled } = officialPrompts()
  const doc = readFileSync(playbookPath, 'utf8')
  for (const quote of quotes) assert.ok(doc.includes(`> ${quote}`))
  assert.ok(doc.includes(`> ${scheduled}`))
  assert.equal((doc.match(/```/g) || []).length % 2, 0)

  const links = [...doc.matchAll(/\[[^\]]+\]\(([^)]+)\)/g)].map(match => match[1])
  assert.ok(links.length >= 3)
  for (const link of links) {
    assert.equal(link.startsWith('http'), false)
    const target = resolve(dirname(playbookPath), link)
    assert.equal(existsSync(target), true, link)
  }

  const tableRows = doc.split('\n').filter(line => line.startsWith('|'))
  assert.ok(tableRows.length >= 4)
  const columns = tableRows[0].split('|').length
  for (const row of tableRows) assert.equal(row.split('|').length, columns)
})
