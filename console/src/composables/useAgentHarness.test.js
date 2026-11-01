import test from 'node:test'
import assert from 'node:assert/strict'
import { createRenderer } from 'vue'
import { useAgentHarness } from './useAgentHarness.js'

const renderer = createRenderer({
  createComment: () => ({}), insert() {}, remove() {}, parentNode() {}, nextSibling() {}
})

function fixture(t) {
  const sessions = ['a', 'b'].map(sessionId => ({ sessionId, title: 'New Chat', runnerId: 'runner' }))
  const streams = new Map()
  let finishRename
  t.mock.method(globalThis, 'fetch', async (url, options = {}) => {
    const body = JSON.parse(options.body || '{}')
    if (url === '/api/v1/sessions/detail') return Response.json({ session: sessions.find(s => s.sessionId === body.sessionId), events: [] })
    if (url === '/api/v1/agent/context') return Response.json({ tools: [] })
    if (url === '/api/v1/sessions') return Response.json(sessions)
    if (url === '/api/v1/sessions/rename') return new Promise(resolve => { finishRename = () => resolve(Response.json(sessions[1])) })
    if (url === '/api/v1/runners/chat') return new Promise((resolve, reject) => {
      streams.set(body.sessionId, { resolve, reject, body })
    })
    throw new Error(`Unexpected request: ${url}`)
  })
  const oldWindow = globalThis.window, oldEvents = globalThis.EventSource
  globalThis.window = { clearTimeout, clearInterval, localStorage: { getItem() { return null } } }
  globalThis.EventSource = class { addEventListener() {} close() {} }
  let harness
  const app = renderer.createApp({ setup() { harness = useAgentHarness({ autoStart: false }); return () => null } })
  app.mount({})
  t.after(() => { app.unmount(); globalThis.window = oldWindow; globalThis.EventSource = oldEvents })
  return { harness, streams, finishRename: () => finishRename?.() }
}

function complete(stream, sessionId, content) {
  const message = { messageId: `saved-${sessionId}`, sessionId, role: 'user', content }
  stream.resolve(new Response([
    `event: message_end\ndata: ${JSON.stringify({ sessionId, payload: { message } })}\n\n`,
    `event: response\ndata: ${JSON.stringify({ sessionId, status: 'COMPLETED' })}\n\n`
  ].join(''), { headers: { 'Content-Type': 'text/event-stream' } }))
}

test('messages appear immediately in their own session while another stream and rename are pending', async t => {
  const { harness: h, streams, finishRename } = fixture(t)
  await h.selectSession('a')
  h.draft.value = 'First message'
  const first = h.sendMessage()
  assert.equal(h.messages.value[0].content, 'First message')
  await h.selectSession('b')
  h.draft.value = 'Second message'
  const second = h.sendMessage()
  assert.deepEqual(h.messages.value.map(m => m.content), ['Second message'])
  assert.equal(h.draft.value, '')
  assert.equal(streams.size, 2, 'rename does not block either request')
  await h.selectSession('a')
  assert.deepEqual(h.messages.value.map(m => m.content), ['First message'])
  await h.selectSession('b')
  complete(streams.get('a'), 'a', 'First message')
  await first
  assert.deepEqual(h.messages.value.map(m => m.content), ['Second message'])
  complete(streams.get('b'), 'b', 'Second message')
  await second
  assert.equal(h.messages.value.length, 1)
  assert.equal(h.messages.value[0].messageId, 'saved-b')
  finishRename()
})

test('rejected request removes the local message and restores its draft', async t => {
  const { harness: h, streams } = fixture(t)
  await h.selectSession('b')
  h.draft.value = 'Retry me'
  const sending = h.sendMessage()
  assert.equal(h.messages.value[0].content, 'Retry me')
  streams.get('b').reject(new Error('Offline'))
  await sending
  assert.equal(h.draft.value, 'Retry me')
  assert.equal(h.messages.value.filter(m => m.role === 'user').length, 0)
  assert.equal(h.running.value, false)
})
