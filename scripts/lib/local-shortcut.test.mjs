import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { test } from 'node:test'

test('desktop shortcuts always launch local mode from their own installation', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'jagent shortcuts '))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const windows = process.platform === 'win32'
  const file = windows ? 'windows/start.cmd' : 'macos/start.command'
  const shortcut = join(root, 'scripts', file)
  mkdirSync(dirname(shortcut), { recursive: true })
  copyFileSync(new URL('../' + file, import.meta.url), shortcut)
  writeFileSync(join(root, 'scripts/start.mjs'), 'console.log(JSON.stringify(process.argv.slice(2)))')
  const command = windows ? process.env.ComSpec : shortcut
  const args = windows ? ['/d', '/c', 'scripts\\windows\\start.cmd', 'frontend'] : ['frontend']
  const output = execFileSync(command, args, { cwd: root, encoding: 'utf8', timeout: 5000 })
  assert.deepEqual(JSON.parse(output), ['local'])
})
