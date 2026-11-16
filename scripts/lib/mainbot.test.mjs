import assert from 'node:assert/strict'
import { test } from 'node:test'
import { setupMainbot } from './mainbot.mjs'

test('setup passes the selected Runner and source without model credentials', async () => {
  const result = await setupMainbot('http://localhost:18181', 'api-token', 'runner_known', '/source with spaces', { version: '2.3.0', revision: 'abc' }, async (url, options) => {
    assert.equal(url, 'http://localhost:18181/api/v1/personal/mainbot/setup')
    assert.equal(options.headers.Authorization, 'Bearer api-token')
    assert.deepEqual(JSON.parse(options.body), { runnerId: 'runner_known', sourceWorkspace: '/source with spaces', sourceVersion: '2.3.0', sourceRevision: 'abc' })
    return Response.json({ id: 'agent_mainbot', runnerId: 'runner_known' })
  })
  assert.equal(result.agent.id, 'agent_mainbot')
})

test('a different existing binding is retained, other failures are not hidden', async () => {
  const retained = await setupMainbot('http://localhost', '', 'other', '/repo', {}, async () => Response.json({ code: 'MAINBOT_ALREADY_BOUND', message: 'Kept existing Runner' }, { status: 409 }))
  assert.equal(retained.retained, true)
  await assert.rejects(setupMainbot('http://localhost', '', 'other', '/repo', {}, async () => Response.json({ message: 'Invalid source path' }, { status: 400 })), /Invalid source path/)
})
