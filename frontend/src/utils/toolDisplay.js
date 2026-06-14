export function messageClass(message) {
  const classes = ['message', message.role]
  if (message.role === 'tool' && isFailedToolMessage(message)) {
    classes.push('failed')
  }
  if (message.stopReason === 'aborted') {
    classes.push('interrupted')
  }
  return classes
}

export function parseJsonObject(value) {
  if (!value || typeof value !== 'string') return null
  try {
    const parsed = JSON.parse(value)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null
  } catch {
    return null
  }
}

export function toolResult(message) {
  return parseJsonObject(message.content) || {}
}

export function isFailedToolMessage(message) {
  return isToolResultObjectError(toolResult(message))
}

function isToolResultObjectError(result) {
  if (!result) return false
  if (result.error) return true
  if (result.timedOut) return true
  return typeof result.exitCode === 'number' && result.exitCode !== 0
}

export function toolStatusLabel(message) {
  return isFailedToolMessage(message) ? 'ERR' : 'OK'
}

export function toolMessageTitle(message) {
  const name = message.toolName || 'tool'
  const failed = isFailedToolMessage(message)
  if (name === 'bash') return failed ? 'Bash command failed' : 'Ran bash command'
  if (name === 'skill') return failed ? 'Skill load failed' : 'Loaded skill'
  if (name === 'read') return failed ? 'Read failed' : 'Read file'
  if (name === 'write') return failed ? 'Write failed' : 'Wrote file'
  if (name === 'edit') return failed ? 'Edit failed' : `Edited ${editFileName(message)}`
  if (name === 'grep') return failed ? 'Search failed' : 'Searched files'
  if (name === 'find') return failed ? 'Find failed' : 'Found files'
  if (name === 'ls') return failed ? 'List failed' : 'Listed directory'
  return failed ? `${name} failed` : `Ran ${name}`
}

export function isEditDiffMessage(message) {
  const result = toolResult(message)
  return message.toolName === 'edit'
    && result.diff
    && Array.isArray(result.diff.hunks)
    && result.diff.hunks.length > 0
}

export function editFileName(message) {
  const result = toolResult(message)
  if (result.fileName) return result.fileName
  if (!result.path) return 'file'
  const parts = String(result.path).split(/[\\/]/).filter(Boolean)
  return parts.length > 0 ? parts[parts.length - 1] : result.path
}

export function editAdditions(message) {
  const value = toolResult(message).additions
  return typeof value === 'number' ? value : 0
}

export function editDeletions(message) {
  const value = toolResult(message).deletions
  return typeof value === 'number' ? value : 0
}

export function editDiffHunks(message) {
  const result = toolResult(message)
  return result.diff && Array.isArray(result.diff.hunks) ? result.diff.hunks : []
}

export function diffHunkKey(hunk) {
  return `${hunk.oldStart || 0}:${hunk.newStart || 0}:${hunk.oldLines || 0}:${hunk.newLines || 0}`
}

export function diffLineClass(line) {
  if (line.type === 'added') return 'added'
  if (line.type === 'removed') return 'removed'
  return 'context'
}

export function diffLinePrefix(line) {
  if (line.type === 'added') return '+'
  if (line.type === 'removed') return '-'
  return ' '
}

export function formatDiffLineNumber(value) {
  return typeof value === 'number' ? value : ''
}

export function toolMessageSubtitle(message) {
  const result = toolResult(message)
  const parts = []
  const target = toolPrimaryTarget(message.toolName, result)
  if (target) parts.push(target)
  if (typeof result.exitCode === 'number') parts.push(`exit ${result.exitCode}`)
  if (result.timedOut) parts.push('timeout')
  if (typeof result.bytes === 'number') parts.push(`${result.bytes} bytes`)
  const count = toolResultCount(result)
  if (count) parts.push(count)
  if (result.error) parts.push(oneLine(result.error, 120))
  return parts.join(' | ') || 'completed'
}

function toolPrimaryTarget(name, result) {
  if (!result) return ''
  if (name === 'bash' && result.command) return oneLine(result.command, 120)
  if (name === 'grep' && result.query) return `"${oneLine(result.query, 60)}" in ${result.path || '.'}`
  if (result.path) return result.path
  if (result.command) return oneLine(result.command, 120)
  return ''
}

function toolResultCount(result) {
  if (Array.isArray(result.matches)) return `${result.matches.length} matches`
  if (Array.isArray(result.entries)) return `${result.entries.length} entries`
  return ''
}

export function toolCommand(message) {
  return toolResult(message).command || ''
}

export function toolStdout(message) {
  return toolResult(message).stdout || ''
}

export function toolStderr(message) {
  return toolResult(message).stderr || ''
}

export function toolTextContent(message) {
  const result = toolResult(message)
  return result.content || ''
}

export function toolResultItems(message) {
  const result = toolResult(message)
  const entries = Array.isArray(result.matches) ? result.matches : result.entries
  if (!Array.isArray(entries)) return []

  const visible = entries.slice(0, 20).map((entry, index) => ({
    key: `${index}:${entry.path || entry.name || ''}:${entry.line || ''}`,
    title: resultItemTitle(entry),
    detail: resultItemDetail(entry)
  }))
  if (entries.length > visible.length) {
    visible.push({
      key: 'more',
      title: `${entries.length - visible.length} more`,
      detail: 'showing first 20 results'
    })
  }
  return visible
}

function resultItemTitle(entry) {
  if (entry.path && entry.line) return `${entry.path}:${entry.line}`
  if (entry.path) return entry.path
  if (entry.name) return entry.name
  return 'result'
}

function resultItemDetail(entry) {
  if (entry.preview) return entry.preview
  if (entry.type) return entry.type
  return ''
}

export function summarizeToolArguments(call) {
  const name = call.name || call.toolName || 'tool'
  const args = parseJsonObject(call.argumentsJson || call.arguments || '') || {}
  if (name === 'bash' && args.command) return oneLine(args.command, 160)
  if (name === 'grep' && args.query) return `"${oneLine(args.query, 80)}" in ${args.path || '.'}`
  if (name === 'skill' || name === 'read' || name === 'write' || name === 'edit' || name === 'ls') {
    return args.path || '.'
  }
  if (name === 'find') return `${args.path || '.'} ${args.glob || '**/*'}`.trim()
  const summary = Object.entries(args)
    .map(([key, value]) => `${key}: ${oneLine(String(value), 80)}`)
    .join(', ')
  return summary || name
}

function oneLine(value, maxLength = 120) {
  const text = String(value || '').replace(/\s+/g, ' ').trim()
  return text.length > maxLength ? `${text.slice(0, maxLength)}...` : text
}
