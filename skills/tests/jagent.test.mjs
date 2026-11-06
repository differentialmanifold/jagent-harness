import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import { mkdtemp, writeFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
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
    if (path === '/api/v1/personal/agents') return [{ id: 'a', name: 'Developer', enabled: true, memory: 'private memory', instructions: 'Implement and verify' }]
    if (path.endsWith('/conversations') && req.method === 'POST') return { sessionId: 's', agentId: 'a', title: req.body.title }
    if (path.endsWith('/conversations/s') && req.method === 'POST') return { id: req.body.messageId, sessionId: 's', agentId: 'a', status: 'PENDING' }
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
  assert.deepEqual(await client.agents(), [{ id: 'a', name: 'Developer', enabled: true, instructions: 'Implement and verify', runnerId: null }])
  const chat = await client.createConversation('a', 'Todo CLI')
  const sent = await client.send('a', chat.sessionId, 'Build and verify a todo CLI')
  assert.match(sent.messageId, /^msg_/)
  const result = await client.wait('a', 's', sent.messageId, { intervalMs: 100 })
  assert.equal(result.timedOut, false)
  assert.equal(result.message.status, 'DONE')
  assert.equal(result.openTasks, 1)
  assert.equal(result.pendingProjectRuns, 1)
  assert.equal((await client.tasks('a', 's')).length, 1)
  assert.equal((await client.history('a', 's', { after: 'm1', limit: 5 })).nextAfter, 'm2')
  assert.equal(state.requests.filter(r => r.method === 'POST').length, 2)
})

test('bootstrap an empty server: discover a Runner, create an Agent and submit a goal', async t => {
  const profiles = []
  const state = await server(t, (req, res) => {
    assert.equal(req.token, 'Bearer bootstrap-token')
    const path = req.url.pathname
    if (path === '/api/v1/runners' && req.method === 'GET') return [{
      runnerId: 'r', name: 'linux-dev', online: true, runtime: 'java', workspaces: true, workspaceRoot: '/work',
      tools: [{ name: 'read', description: 'Read files', parameters: { type: 'object' } }], toolDiagnostics: { read: 'ready' }
    }]
    if (path === '/api/v1/personal/agents') {
      if (req.method === 'POST') {
        assert.deepEqual(req.body, { name: 'Developer', runnerId: 'r', instructions: 'Verify results.' })
        profiles.push({ id: 'a', enabled: true, memory: '', ...req.body })
        return profiles[0]
      }
      return profiles
    }
    if (path === '/api/v1/personal/agents/a/conversations' && req.method === 'POST') return { agentId: 'a', sessionId: 's', title: req.body.title }
    if (path === '/api/v1/personal/agents/a/conversations/s' && req.method === 'POST') return { id: req.body.messageId, agentId: 'a', sessionId: 's', status: 'PENDING' }
    res.statusCode = 404; return { message: 'Unexpected path' }
  })
  const client = new JAgentClient({ server: state.url, token: 'bootstrap-token' })
  assert.deepEqual(await client.agents(), [])
  const [runner] = await client.runners()
  assert.equal(runner.online, true)
  assert.deepEqual(runner.tools, [{ name: 'read', description: 'Read files' }])
  // A caller-supplied ID/revision must never turn creation into a profile update.
  const agent = await client.createAgent({ name: ' Developer ', runnerId: runner.runnerId, instructions: 'Verify results.', id: 'existing', revision: 5 })
  assert.deepEqual(agent, { id: 'a', name: 'Developer', enabled: true, instructions: 'Verify results.', runnerId: 'r' })
  assert.deepEqual(await client.agents(), [agent])
  const chat = await client.createConversation(agent.id, 'Todo CLI')
  assert.equal((await client.send(agent.id, chat.sessionId, 'Implement and verify a todo CLI')).status, 'PENDING')
  assert.equal(state.requests.filter(r => r.method === 'POST').length, 3)
})

test('CLI creates with multiline instructions; SDK supports server-only Agents and validates before writing', async t => {
  const state = await server(t, req => ({ id: 'a', enabled: true, ...req.body }))
  const directory = await mkdtemp(join(tmpdir(), 'jagent-skill-'))
  t.after(() => rm(directory, { recursive: true, force: true }))
  const path = join(directory, 'instructions.txt')
  const instructions = 'Verify results.\nReport evidence with `commands` and $literal text.'
  await writeFile(path, instructions)
  const script = fileURLToPath(new URL('../jagent/scripts/jagent.mjs', import.meta.url))
  const options = { env: { ...process.env, JAGENT_SERVER_URL: state.url, JAGENT_CLIENT_TOKEN: '' } }
  const { stdout } = await run(process.execPath, [script, 'create-agent', '--name', 'Developer', '--runner', 'r', '--instructions-file', path], options)
  assert.equal(JSON.parse(stdout).runnerId, 'r')
  assert.deepEqual(state.requests[0].body, { name: 'Developer', runnerId: 'r', instructions })
  assert.equal(state.requests[0].url.pathname, '/api/v1/personal/agents')
  const client = new JAgentClient({ server: state.url })
  assert.equal((await client.createAgent({ name: 'Planner' })).runnerId, null)
  assert.deepEqual(state.requests[1].body, { name: 'Planner', runnerId: null, instructions: '' })
  await assert.rejects(client.createAgent(), /name must/)
  await assert.rejects(client.createAgent({ name: '   ' }), /name must/)
  await assert.rejects(client.createAgent({ name: 'x'.repeat(129) }), /name must/)
  await assert.rejects(client.createAgent({ name: 'A', runnerId: '../other' }), /valid ID/)
  await assert.rejects(client.createAgent({ name: 'A', instructions: 'x'.repeat(16001) }), /instructions must/)
  const failure = await run(process.execPath, [script, 'create-agent', '--name', 'A', '--instructions', 'one', '--instructions-file', path], options).catch(e => e)
  assert.equal(failure.code, 1)
  assert.match(JSON.parse(failure.stderr).error, /only one/)
  assert.equal(state.requests.length, 2)
})

test('uncertain Agent creation never retries; a subsequent list can recover the created profile', async t => {
  const profiles = []
  const state = await server(t, (req, res) => {
    if (req.method === 'POST') {
      profiles.push({ id: 'a', enabled: true, ...req.body })
      res.destroy()
      return null
    }
    return profiles
  })
  const client = new JAgentClient({ server: state.url })
  await assert.rejects(client.createAgent({ name: 'Developer', runnerId: 'r' }), e => e.code === 'CONNECTION_FAILED')
  const agents = await client.agents()
  assert.equal(agents.length, 1)
  assert.equal(agents[0].runnerId, 'r')
  assert.equal(state.requests.filter(r => r.method === 'POST').length, 1)
})

test('bounded waits never resubmit or cancel a goal', async t => {
  const state = await server(t, () => ({ message: { id: 'm', status: 'RUNNING' } }))
  const result = await new JAgentClient({ server: state.url }).wait('a', 's', 'm', { timeoutMs: 150, intervalMs: 100 })
  assert.equal(result.timedOut, true)
  assert.ok(state.requests.every(r => r.method === 'GET'))
})

test('failed send exposes the original message ID without replaying it', async t => {
  const state = await server(t, (req, res) => { res.destroy(); return null })
  const client = new JAgentClient({ server: state.url, token: 'do-not-print' })
  const error = await client.send('a', 's', 'A goal').catch(error => error)
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
    return [{ id: 'a', name: 'Developer', enabled: true }]
  })
  const script = fileURLToPath(new URL('../jagent/scripts/jagent.mjs', import.meta.url))
  const options = { env: { ...process.env, JAGENT_SERVER_URL: state.url, JAGENT_CLIENT_TOKEN: 'cli-secret' } }
  const { stdout, stderr } = await run(process.execPath, [script, 'agents'], options)
  assert.equal(JSON.parse(stdout)[0].id, 'a')
  assert.equal(stderr, '')
  const failure = await run(process.execPath, [script, 'agents'], { env: { ...options.env, JAGENT_CLIENT_TOKEN: '' } }).catch(e => e)
  assert.equal(failure.code, 1)
  assert.equal(JSON.parse(failure.stderr).status, 401)
  assert.doesNotMatch(failure.stderr, /cli-secret/)
  await assert.rejects(new JAgentClient({ server: state.url }).send('a', '../wrong', 'hello'), /valid ID/)
  assert.throws(() => new JAgentClient({ server: 'https://user:secret@example.com' }), /without credentials/)
})
