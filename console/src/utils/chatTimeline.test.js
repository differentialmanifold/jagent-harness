import test from 'node:test'
import assert from 'node:assert/strict'
import { groupCompletedReplies } from './chatTimeline.js'

const message = (id, role, extra = {}) => ({ messageId: id, role, runId: 'run1', ...extra })

test('keeps live work visible until the run finishes', () => {
  const messages = [message('1', 'assistant'), message('2', 'tool'), message('3', 'assistant')]
  assert.deepEqual(groupCompletedReplies(messages).map(entry => entry.message), messages)
  assert.ok(groupCompletedReplies(messages).every(entry => !entry.process))
})

test('collapses completed work and final reasoning without hiding the answer or user', () => {
  const messages = [message('u', 'user'), message('a', 'assistant'), message('t', 'tool'),
    message('f', 'assistant', { runCompleted: true, content: 'Done', reasoningContent: 'Check', runDurationMillis: 2000 })]
  const entries = groupCompletedReplies(messages)
  assert.equal(entries.length, 2)
  assert.equal(entries[0].message.role, 'user')
  assert.deepEqual(entries[1].process.map(item => item.messageId), ['a', 't', 'f'])
  assert.equal(entries[1].process[2].content, '')
  assert.equal(entries[1].message.content, 'Done')
  assert.equal(entries[1].message.reasoningContent, '')
  assert.equal(entries[1].duration, 2000)
  assert.equal(messages[3].reasoningContent, 'Check')
})

test('does not fold another run or an intervening user input', () => {
  const entries = groupCompletedReplies([
    message('old', 'tool', { runId: 'old' }), message('a', 'assistant'),
    message('u', 'user'), message('t', 'tool'),
    message('f', 'assistant', { runCompleted: true, content: 'Done' })
  ])
  assert.equal(entries.length, 4)
  assert.deepEqual(entries[3].process.map(item => item.messageId), ['t'])
  assert.equal(entries[2].message.role, 'user')
})

test('a direct answer needs no empty disclosure', () => {
  const entries = groupCompletedReplies([message('f', 'assistant', { runCompleted: true, content: 'Hello' })])
  assert.equal(entries.length, 1)
  assert.equal(entries[0].process, undefined)
})


test('quiet background turns retain work without adding an empty answer', () => {
  assert.deepEqual(groupCompletedReplies([message('f', 'assistant', { runCompleted: true, content: '' })]), [])
  const entries = groupCompletedReplies([message('t', 'tool', { content: 'Checked status' }),
    message('f', 'assistant', { runCompleted: true, content: '' })])
  assert.equal(entries.length, 1)
  assert.equal(entries[0].process[0].content, 'Checked status')
  assert.equal(entries[0].message.content, '')
})
