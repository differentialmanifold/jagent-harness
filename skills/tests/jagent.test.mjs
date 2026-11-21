import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import { JAgentClient, JAgentError } from '../jagent/scripts/jagent.mjs'

const run = promisify(execFile)
async function server(t, handler) {
  const requests = []
  const server = createServer(async (req, res) => {
    const chunks = []
    for await (const chunk of req) chunks.push(chunk)
    const raw = Buffer.concat(chunks).toString()
    const request = { method: req.method, url: new URL(req.url, 'http://test'),
      token: req.headers.authorization, body: raw ? JSON.parse(raw) : undefined }
    requests.push(request)
    try {
      const value = await handler(request, res)
      if (!res.destroyed && !res.writableEnded) {
        res.setHeader('Content-Type', 'application/json')
        res.end(JSON.stringify(value))
      }
    } catch (error) { res.statusCode = 500; res.end(JSON.stringify({ message: error.message })) }
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections() }))
  return { url: `http://127.0.0.1:${server.address().port}`, requests }
}

test('delegate using only Agent APIs, preserve receipts, read progress and incrementally read history', async t => {
  let reads = 0
  const state = await server(t, (req, res) => {
    assert.equal(req.token, 'Bearer test-secret')
    const path = req.url.pathname
    assert.ok(path.startsWith('/api/v1/personal/agents/agent_mainbot/'))
    if (path.endsWith('/conversations') && req.method === 'GET') return [{ sessionId: 'initial' }]
    if (path.endsWith('/conversations') && req.method === 'POST') return { sessionId: 's', agentId: 'agent_mainbot', title: req.body.title }
    if (path.endsWith('/conversations/s') && req.method === 'POST') return { id: req.body.messageId, sessionId: 's', agentId: 'agent_mainbot', status: 'PENDING' }
    if (path.endsWith('/status')) return { sessionId: 's', openTasks: 1, pendingProjectRuns: 1,
      message: { id: req.url.searchParams.get('messageId'), status: ++reads > 1 ? 'DONE' : 'RUNNING', answer: 'Started project' } }
    if (path.endsWith('/history')) {
      assert.equal(req.url.searchParams.get('after'), 'm1')
      assert.equal(req.url.searchParams.get('limit'), '5')
      return { messages: [{ messageId: 'm2', role: 'assistant', content: 'Started project' }], nextAfter: 'm2', hasMore: false }
    }
    if (path.endsWith('/cards')) return [{ id: 'task1', conversationId: 's', status: 'DOING', title: 'Implement' }, { id: 'task2', conversationId: 'other' }]
    res.statusCode = 404; return { message: 'Unexpected path' }
  })
  const client = new JAgentClient({ server: state.url, token: 'test-secret' })
  assert.deepEqual(await client.conversations(), [{ sessionId: 'initial' }])
  const chat = await client.createConversation('Todo CLI')
  const sent = await client.send(chat.sessionId, 'Build and verify a todo CLI')
  assert.match(sent.messageId, /^msg_/)
  const result = await client.wait('s', sent.messageId, { intervalMs: 100 })
  assert.equal(result.timedOut, false)
  assert.equal(result.message.status, 'DONE')
  assert.equal(result.openTasks, 1)
  assert.equal(result.pendingProjectRuns, 1)
  assert.equal((await client.tasks('s')).length, 1)
  assert.equal((await client.history('s', { after: 'm1', limit: 5 })).nextAfter, 'm2')
  assert.equal(state.requests.filter(r => r.method === 'POST').length, 2)
})

test('bounded waits never resubmit or cancel a goal', async t => {
  const state = await server(t, () => ({ message: { id: 'm', status: 'RUNNING' } }))
  const result = await new JAgentClient({ server: state.url }).wait('s', 'm', { timeoutMs: 150, intervalMs: 100 })
  assert.equal(result.timedOut, true)
  assert.ok(state.requests.every(r => r.method === 'GET'))
})

test('failed send exposes the original message ID without replaying it', async t => {
  const state = await server(t, (req, res) => { res.destroy(); return null })
  const client = new JAgentClient({ server: state.url, token: 'do-not-print' })
  const error = await client.send('s', 'A goal').catch(error => error)
  assert.ok(error instanceof JAgentError)
  assert.equal(error.code, 'CONNECTION_FAILED')
  assert.match(error.message, /uncertain/)
  assert.equal(error.messageId, state.requests[0].body.messageId)
  assert.equal(state.requests.length, 1)
  assert.doesNotMatch(error.message, /do-not-print/)
})

test('CLI and HTTP errors are machine readable, credentials stay in the header', async t => {
  const state = await server(t, (req, res) => {
    if (req.token !== 'Bearer cli-secret') { res.statusCode = 401; return { code: 'UNAUTHORIZED', message: 'Token required' } }
    assert.equal(req.url.pathname, '/api/v1/personal/agents/agent_mainbot/conversations')
    return [{ sessionId: 's', title: 'Chat 1' }]
  })
  const script = fileURLToPath(new URL('../jagent/scripts/jagent.mjs', import.meta.url))
  const options = { env: { ...process.env, JAGENT_SERVER_URL: state.url, JAGENT_USER_TOKEN: 'cli-secret' } }
  const { stdout, stderr } = await run(process.execPath, [script, 'conversations'], options)
  assert.equal(JSON.parse(stdout)[0].sessionId, 's')
  assert.equal(stderr, '')
  for (const args of [['agents'], ['runners'], ['create-agent'], ['conversations', '--agent', 'other']]) {
    await assert.rejects(run(process.execPath, [script, ...args], options), error => error.code === 1)
  }
  assert.equal(state.requests.length, 1)
  const failure = await run(process.execPath, [script, 'conversations'], { env: { ...options.env, JAGENT_USER_TOKEN: '' } }).catch(e => e)
  assert.equal(failure.code, 1)
  assert.equal(JSON.parse(failure.stderr).status, 401)
  assert.doesNotMatch(failure.stderr, /cli-secret/)
  await assert.rejects(new JAgentClient({ server: state.url }).send('../wrong', 'hello'), /valid ID/)
  assert.throws(() => new JAgentClient({ server: 'https://user:secret@example.com' }), /without credentials/)
})


test('reject empty or malformed successful responses without reporting a connection failure', async t => {
  let body = ''
  const state = await server(t, (req, res) => { res.end(body) })
  const client = new JAgentClient({ server: state.url })
  for (const invalid of ['', '   ', 'null', 'true', '"text"', '{}', '[null]']) {
    body = invalid
    await assert.rejects(client.conversations(), { code: 'INVALID_RESPONSE', status: 200 })
  }
  body = ''
  await assert.rejects(client.status('s'), { code: 'INVALID_RESPONSE' })
  await assert.rejects(client.history('s'), { code: 'INVALID_RESPONSE' })
  await assert.rejects(client.send('s', 'work', { messageId: 'm' }), {
    code: 'INVALID_RESPONSE', messageId: 'm'
  })
  body = '{}'
  await assert.rejects(client.wait('s', 'm'), { code: 'INVALID_RESPONSE' })
  const script = fileURLToPath(new URL('../jagent/scripts/jagent.mjs', import.meta.url))
  await assert.rejects(run(process.execPath, [script, 'conversations'], {
    env: { ...process.env, JAGENT_SERVER_URL: state.url }
  }), error => { assert.equal(JSON.parse(error.stderr).code, 'INVALID_RESPONSE'); return true })
})

test('preserve HTTP failures even when the error body is empty', async t => {
  const state = await server(t, (req, res) => { res.statusCode = 503; res.end() })
  await assert.rejects(new JAgentClient({ server: state.url }).conversations(), { code: 'HTTP_ERROR', status: 503 })
})


test('capability discovery hides connection details and explicit model/images reach a send', async t => {
  const state = await server(t, req => {
    if (req.url.pathname === '/api/v1/models') return { defaultModelId: 'text', models: [{ id: 'visual', name: 'Visual', enabled: true, inputTypes: ['text', 'image'], capabilities: ['visual-analysis'], baseUrl: 'https://private.example', apiKey: 'secret' }] }
    return { id: req.body.messageId, agentId: 'agent_mainbot', sessionId: 's', status: 'PENDING' }
  })
  const client = new JAgentClient({ server: state.url })
  const models = await client.models()
  assert.deepEqual(models.models[0].inputTypes, ['text', 'image'])
  assert.equal(JSON.stringify(models).includes('secret'), false)
  assert.equal(JSON.stringify(models).includes('private.example'), false)
  const images = [{ url: 'data:image/png;base64,aGVsbG8=' }]
  await client.send('s', '', { messageId: 'image', modelId: 'visual', images })
  assert.equal(state.requests[1].body.modelId, 'visual')
  assert.deepEqual(state.requests[1].body.images, images)
})
