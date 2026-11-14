import { isRuntimeContextMessage } from './runtimeContext.js'
import { parseJsonObject } from './toolDisplay.js'

// Inbox polling and the session stream advance independently. Reconcile by run
// identity, never by text: two identical user submissions are distinct messages.
function presentPersonalMessage(message, inboxMessage, agents) {
  if (message.role !== 'user') return message
  const origin = parseJsonObject(message.metadataJson) || {}
  const source = origin.source || inboxMessage?.source
    || (origin.kind === 'agent_message' ? (message.content?.startsWith('[AGENT_REPLY]') ? 'AGENT_REPLY' : 'AGENT') : null)
    || message.content?.match(/^\[(PROJECT|CHECK|RECOVERY|CONTINUE)\]/)?.[1]
  const sender = origin.senderName
    || agents.find(a => a.id === (origin.senderAgentId || inboxMessage?.senderId))?.name
    || origin.senderAgentId || inboxMessage?.senderId || 'Agent'
  const titles = {
    AGENT: `Message from ${sender}`,
    AGENT_REPLY: `Reply from ${sender}`,
    PROJECT: 'Project result received',
    CHECK: 'Scheduled follow-up',
    RECOVERY: 'Checking interrupted work',
    CONTINUE: 'Reviewing the next task'
  }
  // Model input roles describe a conversation with the model, not who spoke in the UI.
  return titles[source] ? { ...message, role: 'status', personalEvent: { source, title: titles[source] } } : message
}

export function pendingPersonalMessages(inbox, timeline, agents = []) {
  const displayedRuns = new Set(timeline.filter(m => m.role === 'user' && !isRuntimeContextMessage(m)).map(m => m.runId).filter(Boolean))
  return inbox.filter(m => ['PENDING', 'RUNNING'].includes(m.status)
    && ['USER', 'FEISHU', 'AGENT', 'AGENT_REPLY'].includes(m.source)
    && !(m.runId && displayedRuns.has(m.runId)))
    .map(m => presentPersonalMessage({ messageId: m.id, runId: m.runId, createdAt: m.createdAt, role: 'user', content: m.text, images: m.images || [],
      metadataJson: JSON.stringify({ source: m.source, senderAgentId: m.senderId, senderName: agents.find(a => a.id === m.senderId)?.name }) }, m, agents))
}

export function personalChatMessages(timeline, inbox, agents = []) {
  const byRun = new Map(inbox.filter(m => m.runId).map(m => [m.runId, m]))
  const entries = timeline.filter(m => !isRuntimeContextMessage(m))
    .map(message => presentPersonalMessage(message, byRun.get(message.runId), agents))
  entries.push(...pendingPersonalMessages(inbox, timeline, agents))
  const runTimes = new Map(inbox.filter(m => m.runId).map(m => [m.runId, m.createdAt]))
  const timestamp = value => typeof value === 'number' ? value : Date.parse(value) || 0
  function insert(message) {
    const runEnd = message.runId ? entries.findLastIndex(m => m.runId === message.runId) : -1
    const later = entries.findIndex(m => timestamp(runTimes.get(m.runId) || m.createdAt) > timestamp(message.createdAt))
    entries.splice(runEnd >= 0 ? runEnd + 1 : later >= 0 ? later : entries.length, 0, message)
  }
  for (const message of inbox) {
    if (message.source === 'NOTICE' && message.answer && !entries.some(m => m.messageId === message.id)) {
      insert({ messageId: message.id, runId: message.runId, createdAt: message.createdAt, role: 'assistant', content: message.answer })
    }
    if (!message.error) continue
    const failure = message.runId ? entries.findIndex(m => m.runId === message.runId && (m.failed || m.stopReason === 'failed')) : -1
    if (failure >= 0) {
      // The inbox has the provider's detailed error; the stream may only say "failed".
      entries[failure] = { ...entries[failure], content: message.error }
    } else {
      insert({ messageId: message.id + ':error', runId: message.runId, createdAt: message.createdAt, role: 'status', failed: true, content: message.error })
    }
  }
  return entries
}
