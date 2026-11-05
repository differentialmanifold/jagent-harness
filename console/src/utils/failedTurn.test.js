import test from 'node:test'
import assert from 'node:assert/strict'
import { excludedInputIds, retryInput } from './failedTurn.js'

test('retries rejected input with its original images and without diagnostic text', () => {
  const image = { url: 'data:image/png;base64,aGk=', mediaType: 'image/png' }
  const input = { messageId: 'u', role: 'user', content: 'describe', images: [image] }
  const failure = { role: 'assistant', content: 'provider rejected request', metadataJson: JSON.stringify({ excludedMessageIds: ['u'] }) }
  assert.deepEqual(retryInput([input, failure], failure), { content: 'describe', images: [image] })
})

test('failure after successful tool work continues instead of resending the original command', () => {
  const input = { messageId: 'u', role: 'user', content: 'refund this order', runId: 'r', turnId: 't' }
  const assistant = { role: 'assistant', runId: 'r', turnId: 't', toolCalls: [{ name: 'refund' }] }
  const failure = { role: 'assistant', failed: true, runId: 'r', turnId: 't' }
  assert.deepEqual(excludedInputIds([input, assistant, failure], failure), [])
  assert.match(retryInput([input, assistant, failure], failure).content, /Do not repeat completed tool operations/)
})
