import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { test } from 'node:test'
import { runnerStateDirectory } from './runner-state.mjs'

test('launcher resolves the same server directories as the Java SDK', t => {
  const root = mkdtempSync(resolve(tmpdir(), 'runner-state-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const cases = [
    ['https://agent.example.com', '3a32a544a8e13ea862fb0e46544b11eb62505f4e93015deee2487e20882633a0'],
    ['http://127.0.0.1:18181', '166f57396bcc750da426bf322777f9361f3815cbbf3c091202d7f10ede7e2573'],
    ['http://[fd7a:115c:a1e0::e937:334e]:18181', '635585ec30d03eedaa8ece0bd86d8fb0df7f41b3b5477b29dc9cc1a7a1ae56e8'],
  ]
  for (const [server, hash] of cases) {
    const expected = resolve(root, 'servers', hash)
    assert.equal(runnerStateDirectory(server, root), expected)
    assert.equal(runnerStateDirectory(`${server}/`, root), expected)
  }
  assert.notEqual(runnerStateDirectory('http://127.0.0.1:18181', root), runnerStateDirectory('http://127.0.0.1:18182', root))
})

test('legacy state stays with its server while another server gets an independent directory', t => {
  const root = mkdtempSync(resolve(tmpdir(), 'runner-state-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const first = 'https://agent.example.com'
  const second = 'https://another.example.com'
  const scoped = runnerStateDirectory(first, root)
  const legacy = JSON.stringify({ server: first, runnerId: 'runner_legacy' })
  writeFileSync(resolve(root, 'config.json'), legacy)
  assert.equal(runnerStateDirectory(`${first}/`, root), root)
  const other = runnerStateDirectory(second, root)
  assert.notEqual(other, root)
  mkdirSync(other, { recursive: true })
  writeFileSync(resolve(other, 'config.json'), JSON.stringify({ server: second, runnerId: 'runner_other' }))
  assert.equal(runnerStateDirectory(second, root), other)
  assert.equal(readFileSync(resolve(root, 'config.json'), 'utf8'), legacy)
  // A previously established scoped identity takes precedence over leftover legacy state.
  mkdirSync(scoped, { recursive: true })
  writeFileSync(resolve(scoped, 'config.json'), JSON.stringify({ server: first, runnerId: 'runner_scoped' }))
  assert.equal(runnerStateDirectory(first, root), scoped)
})


test('launcher rejects origins rejected by the Java runner', () => {
  for (const server of ['http://user:pw@127.0.0.1:18181', 'http://localhost?q=1',
    'http://localhost?', 'http://localhost#', 'http://localhost/path', 'http://localhost/path/..', 'ftp://localhost']) {
    assert.throws(() => runnerStateDirectory(server, tmpdir()), /plain http/)
  }
})
