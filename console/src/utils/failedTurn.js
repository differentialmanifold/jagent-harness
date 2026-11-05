import { isRuntimeContextMessage } from './runtimeContext.js'

export function excludedInputIds(messages, failure) {
  try {
    const metadata = JSON.parse(failure.metadataJson || '{}')
    if (Array.isArray(metadata.excludedMessageIds)) return metadata.excludedMessageIds
  } catch { /* Older timeline-only failures have no receipt. */ }
  const turn = messages.filter(m => m.runId === failure.runId && m.turnId === failure.turnId)
  if (turn.some(m => m.role === 'assistant' && !m.failed && m.stopReason !== 'failed')) return []
  return turn.filter(m => m.role === 'user').map(m => m.messageId)
}

export function retryInput(messages, failure) {
  const ids = new Set(excludedInputIds(messages, failure))
  const inputs = messages.filter(m => ids.has(m.messageId) && m.role === 'user' && !isRuntimeContextMessage(m))
  if (inputs.length) return {
    content: inputs.map(m => m.content || '').join('\n\n'),
    images: inputs.flatMap(m => m.images || [])
  }
  return { content: 'Continue from the interrupted response using the results already available. Do not repeat completed tool operations.', images: [] }
}
