import { isRuntimeContextMessage } from './runtimeContext.js'

// Inbox polling and the session stream advance independently. Reconcile by run
// identity, never by text: two identical user submissions are distinct messages.
export function pendingPersonalMessages(inbox, timeline) {
  const displayedRuns = new Set(timeline.filter(m => m.role === 'user' && !isRuntimeContextMessage(m)).map(m => m.runId).filter(Boolean))
  return inbox.filter(m => ['PENDING', 'RUNNING'].includes(m.status)
    && ['USER', 'FEISHU'].includes(m.source)
    && !(m.runId && displayedRuns.has(m.runId)))
    .map(m => ({ messageId: m.id, runId: m.runId, role: 'user', content: m.text }))
}
