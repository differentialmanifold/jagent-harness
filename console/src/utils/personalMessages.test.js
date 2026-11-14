import test from 'node:test'
import assert from 'node:assert/strict'
import { pendingPersonalMessages, personalChatMessages } from './personalMessages.js'
import { isRuntimeContextMessage } from './runtimeContext.js'
import { groupCompletedReplies } from './chatTimeline.js'
import { messageClass } from './toolDisplay.js'
const input = { id: 'input1', runId: 'run1', source: 'USER', status: 'PENDING', text: 'Continue' }
test('pending inbox and live persisted message produce only one user message', () => {
  assert.equal(pendingPersonalMessages([input], []).length, 1)
  const history = [{ role: 'user', runId: 'run1', messageId: 'persisted1', content: 'Continue' }]
  assert.equal(pendingPersonalMessages([input], history).length, 0)
  assert.equal(pendingPersonalMessages([{ ...input, status: 'RUNNING' }], history).length, 0)
})
test('keeps the placeholder when inbox runs ahead of the stream', () => {
  assert.equal(pendingPersonalMessages([{ ...input, status: 'RUNNING' }], []).length, 1)
})
test('identical submissions and Feishu inputs retain their own identities', () => {
  const history = [{ role: 'user', runId: 'run1', content: 'Continue' }]
  const next = { ...input, id: 'input2', runId: 'run2', source: 'FEISHU' }
  assert.deepEqual(pendingPersonalMessages([input, next], history).map(m => m.messageId), ['input2'])
  assert.equal(pendingPersonalMessages([next], [{ role: 'assistant', runId: 'run2' }]).length, 1)
})
test('internal runtime context neither becomes a user bubble nor removes the pending input', () => {
  const context = { role: 'user', runId: 'run1', content: 'Internal memory and tasks', metadataJson: '{"kind":"runtime_context"}' }
  assert.equal(isRuntimeContextMessage(context), true)
  assert.equal(pendingPersonalMessages([input], [context]).length, 1)
  const user = { role: 'user', runId: 'run1', content: 'Continue' }
  assert.equal(isRuntimeContextMessage(user), false)
  assert.equal(pendingPersonalMessages([input], [context, user]).length, 0)
  assert.equal(isRuntimeContextMessage({ metadataJson: 'legacy-invalid-json' }), false)
})

test('peer placeholders preserve their sender and attachments until history arrives', () => {
  const peer = { id: 'peer', runId: 'peer-run', source: 'AGENT', status: 'PENDING', senderId: 'agent_mac', text: 'Check this', images: [{ name: 'result.png', url: 'data:image/png;base64,eA==' }] }
  const pending = pendingPersonalMessages([peer], [], [{ id: 'agent_mac', name: 'Mac developer' }])
  assert.equal(JSON.parse(pending[0].metadataJson).senderName, 'Mac developer')
  assert.deepEqual(pending[0].images, peer.images)
  assert.equal(pendingPersonalMessages([peer], [{ role: 'user', runId: 'peer-run' }]).length, 0)
})

test('provider errors stay in the failed run and enrich its existing failure only once', () => {
  const failed = { messageId: 'failed', runId: 'run1', role: 'assistant', stopReason: 'failed', content: 'failed' }
  const timeline = [{ messageId: 'u1', runId: 'run1', role: 'user' }, failed,
    { messageId: 'u2', runId: 'run2', role: 'user' }, { messageId: 'a2', runId: 'run2', role: 'assistant', content: 'Recovered' }]
  const inbox = [{ ...input, status: 'FAILED', error: 'Model provider returned HTTP 451', createdAt: 1 }]
  const displayed = personalChatMessages(timeline, inbox)
  assert.deepEqual(displayed.map(m => m.messageId), ['u1', 'failed', 'u2', 'a2'])
  assert.equal(displayed[1].content, inbox[0].error)
  assert.equal(failed.content, 'failed')
})

test('an inbox-only failure is inserted after its run, before later requests', () => {
  const timeline = [{ messageId: 'u1', runId: 'run1', role: 'user' }, { messageId: 'tool1', runId: 'run1', role: 'tool' }, { messageId: 'u2', runId: 'run2', role: 'user' }]
  const inbox = [{ ...input, status: 'FAILED', error: 'Cannot process response', createdAt: 1 }]
  assert.deepEqual(personalChatMessages(timeline, inbox).map(m => m.messageId), ['u1', 'tool1', 'input1:error', 'u2'])
})

test('errors preceding the stream remain before new pending messages', () => {
  const inbox = [{ ...input, status: 'FAILED', error: 'HTTP 451', createdAt: 1 },
    { ...input, id: 'input2', runId: 'run2', createdAt: 2 }]
  assert.deepEqual(personalChatMessages([], inbox).map(m => m.messageId), ['input1:error', 'input2'])
  assert.deepEqual(personalChatMessages([], [{ ...inbox[0], runId: null }, inbox[1]]).map(m => m.messageId), ['input1:error', 'input2'])
})

test('notices keep their historical position and background events remain updates', () => {
  const timeline = [{ messageId: 'user', runId: 'new', role: 'user', createdAt: 3 },
    { messageId: 'check', runId: 'check-run', role: 'user', content: '[CHECK] reminder', createdAt: 4 }]
  const inbox = [{ id: 'notice', source: 'NOTICE', answer: 'Previous update', createdAt: 1 }]
  const displayed = personalChatMessages(timeline, inbox)
  assert.deepEqual(displayed.map(m => m.messageId), ['notice', 'user', 'check'])
  assert.equal(displayed[2].role, 'status')
  assert.equal(personalChatMessages(displayed, inbox).filter(m => m.messageId === 'notice').length, 1)
})


test('peer replies are collapsed process events, not user bubbles, before and after persistence', () => {
  const peer = { id: 'reply', runId: 'reply-run', source: 'AGENT_REPLY', status: 'RUNNING', senderId: 'mac', text: 'Detailed verification' }
  const agents = [{ id: 'mac', name: 'Mac worker' }]
  const pending = personalChatMessages([], [peer], agents)[0]
  const original = { messageId: 'stored-reply', runId: peer.runId, role: 'user', content: '[AGENT_REPLY]\nDetailed verification', metadataJson: JSON.stringify({ kind: 'agent_message', senderAgentId: 'mac', senderName: 'Mac worker' }) }
  const stored = personalChatMessages([original], [peer], agents)
  assert.equal(stored.length, 1)
  for (const message of [pending, stored[0], personalChatMessages([original], [])[0]]) {
    assert.equal(message.role, 'status')
    assert.equal(message.personalEvent.title, 'Reply from Mac worker')
    assert.equal(messageClass(message).includes('user'), false)
    assert.match(message.content, /Detailed verification/)
  }
  assert.equal(original.role, 'user')
  const final = { messageId: 'final', runId: peer.runId, role: 'assistant', runCompleted: true, content: 'Verified successfully' }
  const grouped = groupCompletedReplies([...stored, final])
  assert.equal(grouped.length, 1)
  assert.equal(grouped[0].process[0].personalEvent.source, 'AGENT_REPLY')
  assert.equal(grouped[0].message.content, final.content)
})

test('source metadata works without inbox and user text cannot masquerade as a background event', () => {
  const timeline = [{ messageId: 'project', role: 'user', content: '[PROJECT] Full evidence', metadataJson: '{"kind":"personal_message","source":"PROJECT"}' },
    { messageId: 'typed', role: 'user', runId: 'user-run', content: '[PROJECT] User-typed text' },
    { messageId: 'feishu', role: 'user', content: '[CHECK] User-typed text', metadataJson: '{"source":"FEISHU"}' }]
  const shown = personalChatMessages(timeline, [{ runId: 'user-run', source: 'USER' }])
  assert.equal(shown[0].personalEvent.title, 'Project result received')
  assert.equal(shown[0].content, timeline[0].content)
  assert.equal(shown[1].role, 'user')
  assert.equal(shown[2].role, 'user')
})
