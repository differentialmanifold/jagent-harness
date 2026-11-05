// Only a successful agent_end marks a final reply. A model turn may still call tools.
export function groupCompletedReplies(messages) {
  const entries = []
  for (const message of messages) {
    const key = message.role === 'assistant' && message.turnId && message.stopReason !== 'failed'
      ? `assistant:${message.turnId}` : message.messageId
    if (!message.runCompleted || !message.runId) {
      entries.push({ key, message })
      continue
    }
    let start = entries.length
    while (start > 0) {
      const previous = entries[start - 1]
      if (!previous.message || previous.message.role === 'user'
          || previous.message.runId !== message.runId) break
      start--
    }
    const process = entries.splice(start).map(entry => entry.message)
    if (message.reasoningContent) {
      process.push({ ...message, content: '', images: [], runDurationMillis: null })
    }
    // A quiet background completion has no user-facing reply, but keeps its work trace.
    const silent = !message.content && !message.images?.length
    if (silent && !process.length) continue
    entries.push(process.length ? {
      key,
      process,
      message: { ...message, reasoningContent: '', runDurationMillis: null },
      duration: message.runDurationMillis
    } : { key, message })
  }
  return entries
}
