import test from 'node:test'
import assert from 'node:assert/strict'
import { pendingPersonalMessages } from './personalMessages.js'
import { isRuntimeContextMessage } from './runtimeContext.js'
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
