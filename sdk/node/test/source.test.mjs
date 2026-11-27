import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { syncSource } from '../dist/source.js'

// Two revisions of a ZIP containing a single README.md.
const archives = [
  'UEsDBBQAAAAIAAAAIQBX7nGSBwAAAAUAAAAJAAAAUkVBRE1FLm1kS8ssKi4BAFBLAQIUAxQAAAAIAAAAIQBX7nGSBwAAAAUAAAAJAAAAAAAAAAAAAACAAQAAAABSRUFETUUubWRQSwUGAAAAAAEAAQA3AAAALgAAAAAA',
  'UEsDBBQAAAAIAAAAIQBpER+2CAAAAAYAAAAJAAAAUkVBRE1FLm1kK05Nzs9LAQBQSwECFAMUAAAACAAAACEAaREftggAAAAGAAAACQAAAAAAAAAAAAAAgAEAAAAAUkVBRE1FLm1kUEsFBgAAAAABAAEANwAAAC8AAAAAAA==',
].map(value => Buffer.from(value, 'base64'))
const sha256 = data => createHash('sha256').update(data).digest('hex')

test('source sync reuses a verified build and keeps the previous Project through failed and successful upgrades', async t => {
  const root = await mkdtemp(join(tmpdir(), 'jagent-source-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  const signal = new AbortController().signal
  let bytes = archives[0]
  let hash = sha256(bytes)
  let downloads = 0
  const http = {
    async request() { return { sha256: hash } },
    async download() { downloads++; return bytes },
  }
  const first = await syncSource(http, root, signal)
  assert.deepEqual(await syncSource(http, root, signal), first)
  assert.equal(downloads, 1)
  hash = sha256(archives[1])
  await assert.rejects(syncSource(http, root, signal), /checksum/)
  assert.deepEqual(await readdir(root), [first.harnessSourceSha256])
  bytes = archives[1]
  const second = await syncSource(http, root, signal)
  assert.notEqual(second.harnessSourceWorkspace, first.harnessSourceWorkspace)
  for (const [source, text] of [[first, 'first'], [second, 'second']])
    assert.equal(await readFile(join(source.harnessSourceWorkspace, 'README.md'), 'utf8'), text)
})
