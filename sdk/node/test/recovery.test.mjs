import test from 'node:test'
import { deferred } from '../dist/deferred.js'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { mkdir, mkdtemp, writeFile, rm } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { AgentRunner, success } from '../dist/index.js'

test(
  'a lost tool response and redelivered job survive restart without repeating the effect',
  { timeout: 12000 },
  async (t) => {
    const home = await mkdtemp(join(tmpdir(), 'node-journal-'))
    let phase = 0,
      effects = 0,
      runnerId,
      secret,
      job,
      completed = 0
    const allDelivered = deferred()
    const pending = {
      sessionId: 'ses_test',
      runId: 'run_test',
      status: 'REQUIRES_ACTION',
      toolCalls: [{ turnId: 'turn1', toolCallId: 'call1', name: 'increment', arguments: {} }],
    }
    const server = createServer(async (req, res) => {
      const path = req.url.split('?')[0].replace('/api/v1/', '')
      const chunks = []
      for await (const b of req) chunks.push(b)
      const body = chunks.length ? JSON.parse(Buffer.concat(chunks)) : null
      const json = (value) => {
        res.setHeader('Content-Type', 'application/json')
        res.end(JSON.stringify(value))
      }
      assert.equal(req.headers.authorization, 'Bearer test')
      if (path === 'access/identity') return json({ userId: 'user1' })
      if (!secret) secret = req.headers['x-runner-secret']
      assert.ok(secret)
      assert.equal(req.headers['x-runner-secret'], secret)
      if (path === 'runners') {
        runnerId ??= body.runnerId
        assert.equal(body.runnerId, runnerId)
        job = {
          runnerId,
          request: {
            sessionId: pending.sessionId,
            runId: pending.runId,
            stream: false,
            clientTools: body.tools,
            messages: [{ role: 'user', content: 'increment' }],
          },
        }
        return json({})
      }
      if (path.endsWith('/poll')) {
        if (
          phase === 0 ||
          (phase === 2 && !new URL('http://localhost' + req.url).searchParams.get('activeRun'))
        ) {
          phase++
          return json({ job })
        }
        const timer = setTimeout(() => json({}), 20)
        res.once('close', () => clearTimeout(timer))
        return
      }
      if (path === 'runners/chat') {
        if (body.messages[0].role === 'user') return json(pending)
        assert.deepEqual(body.messages[0], {
          role: 'tool',
          toolCallId: 'call1',
          status: 'SUCCEEDED',
          content: 1,
        })
        if (phase === 1) return res.destroy()
        assert.equal(phase, 4)
        assert.equal(body.messages[1].toolCallId, 'no-record')
        assert.equal(body.messages[1].status, 'UNKNOWN')
        return json({ ...pending, status: 'COMPLETED', toolCalls: [] })
      }
      if (path.endsWith('/complete')) {
        if (phase === 1) {
          phase++
          assert.equal(body.status, 'INTERRUPTED')
          return res.destroy()
        }
        if (phase === 3) {
          assert.equal(body.status, 'INTERRUPTED')
          allDelivered.resolve()
        }
        completed++
        return json({ accepted: true })
      }
      if (path === 'sessions/ses_test/state')
        return json({
          ...pending,
          toolCalls: [...pending.toolCalls, { ...pending.toolCalls[0], toolCallId: 'no-record' }],
        })
      if (path === 'runners/runs/run_test') return json(job)
      if (path.endsWith('/recover')) {
        phase = 4
        return json(job)
      }
      assert.ok(path.endsWith('/events') || path.endsWith('/disconnect'))
      json({ accepted: true })
    })
    server.listen(0, '127.0.0.1')
    await once(server, 'listening')
    const options = {
      home,
      server: 'http://127.0.0.1:' + server.address().port,
      token: 'test',
      tools: [
        {
          name: 'increment',
          description: 'Increment once',
          parameters: { type: 'object' },
          execute() {
            return success(++effects)
          },
        },
      ],
    }
    const runner = await AgentRunner.create(options)
    t.after(async () => {
      await runner.close()
      server.closeAllConnections()
      await new Promise((ok) => server.close(ok))
      await rm(home, { recursive: true, force: true })
    })
    await assert.rejects(AgentRunner.create(options), /in use/)
    const serving = runner.serve()
    await allDelivered.promise
    await runner.close()
    await serving
    const restored = await AgentRunner.create(options)
    assert.equal((await restored.recover('ses_test')).status, 'COMPLETED')
    assert.equal(effects, 1)
    assert.equal(completed, 2)
  },
)

test(
  'an upgrade reuses the bound Runner and job receipt, while a second user gets a separate identity',
  { timeout: 10000 },
  async (t) => {
    const home = await mkdtemp(join(tmpdir(), 'jagent-upgrade-'))
    const completed = deferred()
    let offered = false,
      effects = 0
    const runId = 'run_saved',
      sessionId = 'ses_saved',
      runnerId = 'runner_existing'
    const response = { runId, sessionId, status: 'COMPLETED', answer: 'saved outcome' }
    const wire = createServer(async (req, res) => {
      const chunks = []
      for await (const chunk of req) chunks.push(chunk)
      const body = chunks.length ? JSON.parse(Buffer.concat(chunks)) : null
      let reply = {}
      if (req.url.endsWith('/identity'))
        reply = { userId: req.headers.authorization === 'Bearer first' ? 'local' : 'usr_other' }
      else if (req.url.includes('/poll?') && !offered) {
        offered = true
        reply = {
          job: {
            runnerId,
            request: {
              runId,
              sessionId,
              messages: [{ role: 'user', content: 'do once' }],
              clientTools: [],
            },
          },
        }
      } else if (req.url.endsWith('/complete')) completed.resolve(body)
      else if (req.url.endsWith('/chat')) {
        effects++
        reply = response
      }
      res.setHeader('Content-Type', 'application/json')
      res.end(JSON.stringify(reply))
    })
    wire.listen(0, '127.0.0.1')
    await once(wire, 'listening')
    t.after(async () => {
      wire.closeAllConnections()
      await new Promise((ok) => wire.close(ok))
      await rm(home, { recursive: true, force: true })
    })
    const server = 'http://127.0.0.1:' + wire.address().port
    const base = join(home, 'servers', createHash('sha256').update(server).digest('hex'))
    await mkdir(join(base, 'jobs'), { recursive: true })
    await writeFile(
      join(base, 'config.json'),
      JSON.stringify({ runnerId, server, secret: 'old-secret' }),
    )
    await writeFile(join(base, 'jobs', runId + '.json'), JSON.stringify(response))
    const options = {
      server,
      home,
      tools: [{ name: 'clock', parameters: {}, description: 'Clock', execute: () => success(1) }],
    }
    const first = await AgentRunner.create({ ...options, token: 'first' })
    const other = await AgentRunner.create({ ...options, token: 'second' })
    assert.equal(first.identity.runnerId, runnerId)
    assert.notEqual(other.identity.runnerId, runnerId)
    await other.close()
    const serving = first.serve()
    try {
      assert.deepEqual(await completed.promise, response)
      assert.equal(effects, 0)
    } finally {
      await first.close()
      await serving
    }
  },
)
