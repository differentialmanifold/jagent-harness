import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { mkdtemp, rm, readFile, writeFile, mkdir, link, realpath } from 'node:fs/promises'
import { codingEnvironment, LocalWorkspaces } from '../dist/index.js'
import { search, ripgrep } from '../dist/coding/search.js'
async function fixture(t) {
  const workspace = await realpath(await mkdtemp(join(tmpdir(), 'jagent-node-tools-')))
  t.after(() => rm(workspace, { recursive: true, force: true }))
  const ctx = {
    workspace,
    sessionId: 'ses_fixture',
    runId: 'run_fixture',
    signal: new AbortController().signal,
  }
  const { tools } = codingEnvironment()
  return { workspace, ctx, call: (name, args) => tools.find((t) => t.name === name).execute(args, ctx) }
}
test('coding tools preserve line ranges, content hash, images and atomic batch edit results', async (t) => {
  const { workspace, call } = await fixture(t)
  const source = '\uFEFFone\r\ntwo\r\nthree\r\n'
  const path = 'text.txt',
    file = join(workspace, path)
  assert.equal((await call('write', { path, content: source })).content.bytes, Buffer.byteLength(source))
  const read = (await call('read', { path, offset: 2, limit: 1 })).content
  assert.deepEqual([read.content, read.totalLines, read.truncated], ['two', 3, true])
  assert.equal(read.contentHash, createHash('sha256').update(source).digest('hex'))
  const edits = [
    { oldText: 'three\n', newText: '3\n' },
    { oldText: 'one', newText: '1' },
  ]
  const result = await call('edit', { path, edits })
  assert.equal(result.status, 'SUCCEEDED')
  assert.equal(result.content.previousHash, read.contentHash)
  assert.deepEqual(
    result.content.appliedEdits.map((e) => [e.index, e.matchStrategy]),
    [
      [0, 'line_endings'],
      [1, 'exact'],
    ],
  )
  assert.equal(result.content.additions, 2)
  assert.equal(result.content.deletions, 2)
  assert.ok(result.content.diff.hunks[0].lines.some((l) => l.type === 'added' && l.content === '1'))
  const expected = '\uFEFF1\r\ntwo\r\n3\r\n'
  assert.equal(await readFile(file, 'utf8'), expected)
  const bad = await call('edit', {
    path,
    edits: [
      { oldText: 'two', newText: '2' },
      { oldText: 'absent', newText: '?' },
    ],
  })
  assert.equal(bad.content.code, 'SEARCH_NOT_FOUND')
  assert.equal(await readFile(file, 'utf8'), expected)
  assert.deepEqual((await call('ls', {})).content.entries, [{ name: path, path, type: 'file' }])
  const data = Buffer.from('89504e470d0a1a0a00000000', 'hex')
  await writeFile(join(workspace, 'image.png'), data)
  assert.deepEqual((await call('read', { path: 'image.png' })).images, [
    {
      name: 'image.png',
      mediaType: 'image/png',
      url: 'data:image/png;base64,' + data.toString('base64'),
    },
  ])
  await assert.rejects(() => call('read', { path: 'image.png', offset: 1 }), /text files/)
})
test('concurrent, ambiguous, overlapping and hard-linked edits', async (t) => {
  const { workspace, call } = await fixture(t)
  const path = 'a.txt',
    file = join(workspace, path)
  await writeFile(file, 'aaa\nbefore\nafter\n')
  const replace = (a, b) => call('edit', { path, edits: [{ oldText: a, newText: b }] })
  assert.equal((await replace('aa', 'b')).content.matchCount, 2)
  assert.deepEqual(
    (
      await call('edit', {
        path,
        edits: [
          { oldText: 'bef', newText: 'x' },
          { oldText: 'efore', newText: 'y' },
        ],
      })
    ).content.conflictingEditIndexes,
    [0, 1],
  )
  assert.deepEqual(
    (await Promise.all([replace('before', 'first'), replace('after', 'second')])).map((r) => r.status),
    ['SUCCEEDED', 'SUCCEEDED'],
  )
  await link(file, join(workspace, 'alias.txt'))
  assert.equal((await replace('first', 'third')).content.code, 'HARD_LINK_UNSUPPORTED')
  assert.equal(await readFile(file, 'utf8'), 'aaa\nfirst\nsecond\n')
})
test('workspace browse and managed directories', async (t) => {
  const { workspace } = await fixture(t)
  const workspaces = new LocalWorkspaces(workspace)
  const path = join(workspace, 'agents', 'agent_1', 'project_1')
  assert.equal(await workspaces.resolve(path), path)
  assert.ok((await workspaces.browse(workspace)).directories.includes(join(workspace, 'agents')))
  await assert.rejects(workspaces.resolve(join(workspace, 'other')))
  await assert.rejects(workspaces.resolve('relative'))
})
test('search backends agree on globs, hidden files, ignore directories, Unicode and bounded output', async (t) => {
  const { ctx, workspace } = await fixture(t)
  await mkdir(join(workspace, 'src', 'build'), { recursive: true })
  await writeFile(join(workspace, 'src', '.hidden.txt'), '  match ٣  \r\nmatch 2\n')
  await writeFile(join(workspace, 'src', 'build', 'generated.txt'), 'match 4\n')
  for (const engine of new Set([undefined, ripgrep()])) {
    const found = await search('find', engine, { pattern: 'src/**/*.txt' }, ctx)
    assert.deepEqual(found.content.files, ['src/.hidden.txt'])
    const result = await search(
      'grep',
      engine,
      { pattern: 'MATCH\\s+\\d+', ignoreCase: true, glob: '*.txt', limit: 1 },
      ctx,
    )
    assert.equal(result.content.truncated, true)
    assert.deepEqual(result.content.matches, [{ path: 'src/.hidden.txt', line: 1, preview: 'match ٣' }])
    assert.equal(
      (await search('grep', engine, { pattern: '\\d+', literal: true }, ctx)).content.matches.length,
      0,
    )
  }
})
test('bash exposes isolated browser session, errors, timeout and cancellation', async (t) => {
  const { call } = await fixture(t)
  const success = await call('bash', { command: 'echo "$PLAYWRIGHT_CLI_SESSION"' })
  assert.equal(
    success.content.stdout.trim(),
    'ja-' + createHash('sha256').update('ses_fixture').digest('hex').slice(0, 20),
  )
  assert.equal((await call('bash', { command: 'exit 7' })).content.exitCode, 7)
  const timeout = await call('bash', { command: 'sleep 15', timeoutSeconds: 1 })
  assert.equal(timeout.status, 'UNKNOWN')
  assert.equal(timeout.content.timedOut, true)
})

test('million-character shell results drain to bounded UTF-8 tails on both pipes', async (t) => {
  const { call } = await fixture(t)
  const command =
    "node -e \"const s='界😀'.repeat(350000);process.stdout.write(s+'STDOUT-END');process.stderr.write(s+'STDERR-END');process.exitCode=7\""
  const result = await call('bash', { command })
  assert.equal(result.status, 'FAILED')
  assert.equal(result.content.exitCode, 7)
  for (const [stream, end] of [
    ['stdout', 'STDOUT-END'],
    ['stderr', 'STDERR-END'],
  ]) {
    const output = result.content[stream]
    assert.ok(Buffer.byteLength(output) <= 6 * 1024)
    assert.ok(output.startsWith('[output truncated'))
    assert.ok(output.endsWith(end))
    assert.ok(!output.includes('\uFFFD'))
  }
})

test('minified files produce bounded search pages and readable file metadata', async (t) => {
  const { workspace, ctx, call } = await fixture(t)
  const large = 'MATCH' + '界😀'.repeat(350000) + 'END'
  const content = [large, ...Array(80).fill('MATCH' + '界'.repeat(240))].join('\n')
  const path = 'bundle.min.js'
  await writeFile(join(workspace, path), content)
  const file = (await call('read', { path, limit: 1 })).content
  assert.equal(file.contentHash, createHash('sha256').update(content).digest('hex'))
  assert.equal(file.truncated, true)
  assert.equal(file.totalLines, 81)
  assert.ok(Buffer.byteLength(file.content) <= 8192)
  for (const engine of new Set([undefined, ripgrep()])) {
    const { matches, truncated } = (await search('grep', engine, { pattern: 'MATCH', path }, ctx))
      .content
    assert.equal(truncated, true)
    assert.ok(matches.length > 1 && matches.length < 81)
    assert.equal(matches[0].line, 1)
    assert.ok(matches[0].preview.endsWith('END'))
    assert.ok(Buffer.byteLength(JSON.stringify(matches)) <= 12 * 1024)
    for (const { preview } of matches) {
      assert.ok(Buffer.byteLength(preview) <= 512)
      assert.ok(preview.includes('[truncated]') && !preview.includes('\uFFFD'))
    }
  }
})
