import { test } from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { once } from 'node:events'
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { resolve } from 'node:path'
import { createConsoleServer } from '../../console/serve.mjs'

test('offline Console serves only UI files and proxies authenticated streaming requests', async t => {
  const dir = mkdtempSync(resolve(tmpdir(), 'jagent-console-'))
  mkdirSync(resolve(dir, 'console'))
  writeFileSync(resolve(dir, 'console/index.html'), '<h1>mainbot</h1>')
  writeFileSync(resolve(dir, 'secret'), 'not a UI asset')
  const backend = http.createServer((req, res) => {
    assert.equal(req.headers.authorization, undefined)
    assert.equal(req.headers.cookie, 'jagent_session=browser-session')
    assert.equal(req.url, '/api/v1/chat')
    res.writeHead(200, { 'Content-Type': 'text/event-stream' })
    req.on('data', body => res.write(`data: ${body}\n\n`))
    req.on('end', () => res.end('data: done\n\n'))
  }).listen(0, '127.0.0.1')
  await once(backend, 'listening')
  const ui = createConsoleServer(resolve(dir, 'console'), `http://127.0.0.1:${backend.address().port}`).listen(0, '127.0.0.1')
  await once(ui, 'listening')
  t.after(() => { ui.close(); ui.closeAllConnections(); backend.close(); backend.closeAllConnections(); rmSync(dir, { recursive: true, force: true }) })
  const base = `http://127.0.0.1:${ui.address().port}`
  assert.equal(await (await fetch(base)).text(), '<h1>mainbot</h1>')
  assert.equal((await fetch(`${base}/..%2fsecret`)).status, 404)
  const result = await fetch(`${base}/api/v1/chat`, { method: 'POST', headers: { Cookie: 'jagent_session=browser-session' }, body: 'hello' })
  assert.equal(result.headers.get('content-type'), 'text/event-stream')
  assert.equal(await result.text(), 'data: hello\n\ndata: done\n\n')
})
