import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { request } from '../api/http'
import { loadJson, saveJson } from '../utils/storage'
import { projectName, projectPathLabel } from '../utils/workspace'

const ignoredCoreEvents = new Set([
  'agent_start',
  'turn_start',
  'turn_end'
])

const DEFAULT_PROJECT_NAME = 'Default Project'

export function useAgentHarness() {
  const consoleTarget = import.meta.env.VITE_JAGENT_CONSOLE_TARGET === 'business' ? 'business' : 'coding'
  const workspaceProjectsEnabled = consoleTarget === 'coding'
  const sessions = ref([])
  const currentSession = ref(null)
  const messages = ref([])
  const tools = ref([])
  const agentContext = ref({
    tools: [],
    promptFiles: [],
    skills: [],
    configRoot: '',
    workspaceRoot: ''
  })
  const provider = ref(null)
  const draft = ref('')
  const projectNameDraft = ref('')
  const projectWorkspaceDraft = ref('')
  const projectError = ref('')
  const renameTitleDraft = ref('')
  const renameError = ref('')
  const running = ref(false)
  const stopping = ref(false)
  const stopReady = ref(false)
  const approvalMode = ref(loadJson('jagent.approvalMode', 'ask_approval'))
  const hiddenProjectKeys = ref(loadJson('jagent.hiddenProjects', []))
  const projectAliases = ref(loadJson('jagent.projectAliases', {}))
  const projectDialogOpen = ref(false)
  const projectSubmitting = ref(false)
  const renameDialogOpen = ref(false)
  const renameSubmitting = ref(false)
  const renameTargetType = ref('chat')
  const renameSessionId = ref('')
  const renameProjectKey = ref('')
  let activeStreamId = null
  let activeRequestId = null
  let streamController = null
  let stopFallbackTimer = null
  let stopRequested = false
  const pendingToolMessages = new Map()

  const providerLabel = computed(() => {
    if (!provider.value) return 'Loading runtime'
    return `${provider.value.model} via ${provider.value.activeProvider}`
  })

  const statusText = computed(() => {
    if (!currentSession.value) return 'Create or select a session to begin'
    const pendingApproval = messages.value.find((message) => message.role === 'tool' && message.approval?.pending)
    const runningTool = messages.value.find((message) => message.role === 'tool' && message.running)
    const thinking = messages.value.find((message) => message.role === 'assistant' && message.thinking)
    const prefix = stopping.value
      ? 'Stopping agent run'
      : running.value && !stopReady.value
        ? 'Starting agent run'
      : pendingApproval
        ? `Waiting for ${pendingApproval.toolName || 'tool'} approval`
      : runningTool
        ? `Running ${runningTool.toolName || 'tool'}`
      : thinking
        ? 'Agent is thinking'
      : running.value
        ? 'Agent loop is streaming events'
        : `${messages.value.length} messages`
    return `${prefix} | ${currentProjectLabel(currentSession.value.workspacePath)}`
  })

  const currentProjectKey = computed(() => currentSession.value ? projectKey(sessionProjectName(currentSession.value)) : '')
  const renameDialogTitle = computed(() => renameTargetType.value === 'project' ? 'Rename project' : 'Rename chat')
  const renameDialogLabel = computed(() => renameTargetType.value === 'project' ? 'Project name' : 'Chat title')

  const projectGroups = computed(() => {
    const groups = new Map()
    for (const session of sessions.value) {
      const sessionProjectNameValue = sessionProjectName(session)
      const key = projectKey(sessionProjectNameValue)
      if (hiddenProjectKeys.value.includes(key)) continue
      if (!groups.has(key)) {
        groups.set(key, {
          key,
          projectName: sessionProjectNameValue,
          workspacePath: session.workspacePath || '',
          name: projectAliases.value[key] || sessionProjectNameValue,
          pathLabel: session.workspacePath ? projectPathLabel(session.workspacePath) : 'No workspace',
          sessions: []
        })
      }
      groups.get(key).sessions.push(session)
    }
    return Array.from(groups.values())
  })

  onMounted(bootstrap)
  onBeforeUnmount(() => {
    clearStopFallback()
    if (streamController) {
      streamController.abort()
    }
  })

  async function bootstrap() {
    await Promise.all([loadProvider(), loadAgentContext(), loadSessions()])
    const first = firstVisibleSession()
    if (!currentSession.value && first) {
      await selectSession(first.sessionId)
    }
  }

  async function loadProvider() {
    provider.value = await request('/api/providers')
  }

  async function loadAgentContext(sessionId = null) {
    const body = sessionId ? { sessionId } : {}
    const context = await request('/api/agent/context', {
      method: 'POST',
      body: JSON.stringify(body)
    })
    agentContext.value = context || {
      tools: [],
      promptFiles: [],
      skills: [],
      configRoot: '',
      workspaceRoot: ''
    }
    tools.value = agentContext.value.tools || []
  }

  async function loadSessions() {
    sessions.value = await request('/api/sessions')
  }

  async function createSession(project = null) {
    const targetProjectName = normalizeProjectName(
      project && project.projectName
        ? project.projectName
        : currentSession.value
          ? sessionProjectName(currentSession.value)
          : DEFAULT_PROJECT_NAME)
    const targetWorkspace = workspaceProjectsEnabled
      ? project && project.workspacePath
        ? project.workspacePath
        : currentSession.value && currentSession.value.workspacePath
          ? currentSession.value.workspacePath
          : ''
      : ''
    projectError.value = ''
    showProject(projectKey(targetProjectName))
    const title = `New Chat - ${projectAliases.value[projectKey(targetProjectName)] || targetProjectName}`
    const session = await request('/api/sessions', {
      method: 'POST',
      body: JSON.stringify({
        title,
        workspacePath: targetWorkspace,
        projectName: targetProjectName
      })
    })
    await loadSessions()
    await selectSession(session.sessionId)
  }

  async function refreshAfterDeletion(deletedSessionIds) {
    sessions.value = sessions.value.filter((session) => !deletedSessionIds.includes(session.sessionId))
    if (currentSession.value && deletedSessionIds.includes(currentSession.value.sessionId)) {
      currentSession.value = null
      messages.value = []
    }
    const first = firstVisibleSession()
    if (!currentSession.value && first) {
      await selectSession(first.sessionId)
    } else if (!currentSession.value) {
      await loadAgentContext()
    }
    await loadSessions()
  }

  async function openProjectDialog() {
    projectError.value = ''
    projectNameDraft.value = ''
    projectWorkspaceDraft.value = ''
    projectDialogOpen.value = true
  }

  function closeProjectDialog() {
    if (projectSubmitting.value) return
    projectDialogOpen.value = false
    projectError.value = ''
  }

  async function submitProjectDialog() {
    const projectName = normalizeProjectName(projectNameDraft.value)
    const workspacePath = projectWorkspaceDraft.value.trim()
    if (!projectName || (workspaceProjectsEnabled && !workspacePath)) return
    projectError.value = ''
    projectSubmitting.value = true
    try {
      await createSession({
        projectName,
        workspacePath: workspaceProjectsEnabled ? workspacePath : ''
      })
      projectDialogOpen.value = false
      projectNameDraft.value = ''
      projectWorkspaceDraft.value = ''
    } catch (error) {
      projectError.value = error.message
    } finally {
      projectSubmitting.value = false
    }
  }

  async function createSessionFromProject(project) {
    await createSession(project)
  }

  async function removeProject(project) {
    if (running.value) return
    if (!window.confirm(`Remove project "${project.name}" from the sidebar? Chats will not be deleted.`)) return
    hideProject(project.key)
    if (currentProjectKey.value === project.key) {
      currentSession.value = null
      messages.value = []
      const first = firstVisibleSession()
      if (first) {
        await selectSession(first.sessionId)
      } else {
        await loadAgentContext()
      }
    }
  }

  async function removeSession(session) {
    if (running.value) return
    if (!window.confirm(`Remove chat "${session.title}"? This will delete the chat.`)) return
    try {
      await request('/api/sessions/delete', {
        method: 'POST',
        body: JSON.stringify({ sessionId: session.sessionId })
      })
      await refreshAfterDeletion([session.sessionId])
    } catch (error) {
      projectError.value = error.message
    }
  }

  function openChatRenameDialog(session) {
    if (running.value) return
    renameTargetType.value = 'chat'
    renameSessionId.value = session.sessionId
    renameProjectKey.value = ''
    renameTitleDraft.value = session.title || ''
    renameError.value = ''
    renameDialogOpen.value = true
  }

  function openProjectRenameDialog(project) {
    renameTargetType.value = 'project'
    renameSessionId.value = ''
    renameProjectKey.value = project.key
    renameTitleDraft.value = project.name || ''
    renameError.value = ''
    renameDialogOpen.value = true
  }

  function closeRenameDialog() {
    if (renameSubmitting.value) return
    renameDialogOpen.value = false
    renameError.value = ''
  }

  async function submitRenameDialog() {
    const title = renameTitleDraft.value.trim()
    if (!title) return
    renameError.value = ''
    renameSubmitting.value = true
    try {
      if (renameTargetType.value === 'project') {
        projectAliases.value = {
          ...projectAliases.value,
          [renameProjectKey.value]: title
        }
        saveJson('jagent.projectAliases', projectAliases.value)
        renameDialogOpen.value = false
        return
      }

      if (!renameSessionId.value) return
      const updated = await request('/api/sessions/rename', {
        method: 'POST',
        body: JSON.stringify({ sessionId: renameSessionId.value, title })
      })
      await loadSessions()
      if (currentSession.value && currentSession.value.sessionId === updated.sessionId) {
        currentSession.value = updated
      }
      renameDialogOpen.value = false
    } catch (error) {
      renameError.value = error.message
    } finally {
      renameSubmitting.value = false
    }
  }

  async function selectProject(project) {
    if (!project.sessions.length) return
    await selectSession(project.sessions[0].sessionId)
  }

  async function selectSession(id) {
    const details = await request('/api/sessions/detail', {
      method: 'POST',
      body: JSON.stringify({ sessionId: id })
    })
    currentSession.value = details.session
    replayTimelineEvents(details.events || [])
    await loadAgentContext(id)
  }

  async function sendMessage() {
    if (!currentSession.value || !draft.value.trim()) return
    const content = draft.value
    const sessionId = currentSession.value.sessionId
    if (shouldAutoNameChat(currentSession.value, messages.value)) {
      await renameChatFromPrompt(sessionId, content)
    }
    draft.value = ''
    running.value = true
    stopping.value = false
    stopReady.value = false
    stopRequested = false
    resetTransientRunState()
    activeRequestId = null
    streamController = new AbortController()
    try {
      await streamRequest(
        '/api/chat/stream',
        { sessionId, content, approvalMode: approvalMode.value },
        streamController.signal
      )
      await selectSession(sessionId)
      await loadSessions()
    } catch (error) {
      if (!(error.name === 'AbortError' && stopRequested)) {
        appendLocalErrorMessage(error.message)
      }
    } finally {
      clearStopFallback()
      activeRequestId = null
      streamController = null
      stopRequested = false
      stopping.value = false
      stopReady.value = false
      running.value = false
    }
  }

  async function stopMessage() {
    if (!running.value || stopping.value || !stopReady.value || !activeRequestId) return
    stopping.value = true
    stopRequested = true
    stopFallbackTimer = window.setTimeout(() => {
      if (streamController) {
        streamController.abort()
      }
    }, 3000)
    try {
      await request('/api/chat/requests/stop', {
        method: 'POST',
        body: JSON.stringify({ requestId: activeRequestId })
      })
    } catch (error) {
      clearStopFallback()
      stopRequested = false
      stopping.value = false
      appendLocalErrorMessage(`Failed to stop agent: ${error.message}`)
    }
  }

  async function streamRequest(url, body, signal) {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal
    })
    if (!response.ok || !response.body) {
      const error = await response.json().catch(() => ({ message: response.statusText }))
      throw new Error(error.message || response.statusText)
    }
    activeRequestId = response.headers.get('X-Request-Id')
    if (!activeRequestId) {
      throw new Error('Chat stream response is missing X-Request-Id')
    }
    stopReady.value = true

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      buffer = await consumeSseBuffer(buffer)
    }
    buffer += decoder.decode()
    await consumeSseBuffer(`${buffer}\n\n`)
  }

  async function consumeSseBuffer(buffer) {
    const normalized = buffer.replace(/\r\n/g, '\n')
    const blocks = normalized.split('\n\n')
    const rest = blocks.pop() || ''
    for (const block of blocks) {
      await consumeSseBlock(block)
    }
    return rest
  }

  async function consumeSseBlock(block) {
    const lines = block.split('\n')
    let eventName = 'message'
    const data = []
    for (const line of lines) {
      if (line.startsWith('event:')) eventName = line.slice(6).trim()
      if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
    }
    if (data.length === 0) return
    const event = JSON.parse(data.join('\n'))
    const payload = parsePayload(event.payloadJson)
    const type = event.type || eventName
    handleAgentEvent(eventName, event)
    if (type === 'tool_execution_start'
        || (type === 'message_end' && payload.message?.role === 'assistant' && payload.message.toolCalls?.length)) {
      await waitForBrowserPaint()
    }
  }

  function replayTimelineEvents(events) {
    resetTransientRunState()
    messages.value = []
    for (const event of events) {
      handleAgentEvent(event.type || 'message', event, { replay: true })
    }
    resetTransientRunState()
  }

  function handleAgentEvent(eventName, event, options = {}) {
    const payload = parsePayload(event.payloadJson)
    const type = event.type || eventName
    if (type === 'message_start') {
      if (!options.replay) {
        startThinkingMessage(event, payload)
      }
    } else if (type === 'message_update') {
      if (!options.replay) {
        appendStreamingText(event, payload)
      }
    } else if (type === 'message_reasoning_update') {
      if (!options.replay) {
        appendStreamingReasoning(event, payload)
      }
    } else if (type === 'message_end') {
      mergeMessage(payload.message)
    } else if (type === 'tool_execution_start') {
      if (!options.replay) {
        startToolExecution(event, payload)
      }
    } else if (type === 'tool_execution_update') {
      if (!options.replay) {
        updateToolExecution(payload)
      }
    } else if (type === 'tool_approval_requested') {
      if (!options.replay) {
        requestToolApproval(event, payload)
      }
    } else if (type === 'tool_approval_resolved') {
      if (!options.replay) {
        resolveToolApprovalEvent(payload)
      }
    } else if (type === 'tool_execution_end') {
      if (!options.replay) {
        completeToolExecution(payload)
      }
    } else if (type === 'compaction_start') {
      upsertCompactionMessage(event, payload, true)
    } else if (type === 'compaction_end') {
      upsertCompactionMessage(event, payload, false)
    } else if (type === 'model_retry') {
      appendModelRetryMessage(event, payload)
    } else if (type === 'agent_end') {
      clearEmptyThinkingMessage()
      completePendingTools('completed')
      running.value = false
    } else if (type === 'agent_stopped') {
      clearStopFallback()
      clearEmptyThinkingMessage()
      completePendingTools('stopped')
      activeStreamId = null
      stopping.value = false
      stopReady.value = false
      running.value = false
    } else if (type === 'agent_error') {
      failActiveStreamingMessage()
      completePendingTools('failed')
      throw new Error(payload.message || 'Agent stream failed')
    } else if (!ignoredCoreEvents.has(type)) {
      appendCustomEventMessage(event, type, payload)
    }
  }

  function appendLocalErrorMessage(message) {
    messages.value.push({
      messageId: `local-error:${Date.now()}`,
      sessionId: currentSession.value ? currentSession.value.sessionId : '',
      role: 'assistant',
      content: `Agent error: ${message || 'Unknown error'}`
    })
  }

  function appendModelRetryMessage(event, payload) {
    const attempt = payload.nextAttempt || payload.attempt || '?'
    const maxAttempts = payload.maxAttempts || '?'
    const delay = typeof payload.delayMillis === 'number' && payload.delayMillis > 0
      ? ` in ${formatDelay(payload.delayMillis)}`
      : ''
    const lines = [
      `Model request failed. Retrying attempt ${attempt} of ${maxAttempts}${delay}.`
    ]
    if (payload.error) {
      lines.push(`Original error: ${payload.error}`)
    }
    messages.value.push({
      messageId: `model-retry:${event.eventId || Date.now()}`,
      sessionId: event.sessionId,
      role: 'status',
      content: lines.join('\n'),
      kind: 'model-retry'
    })
  }

  function formatDelay(delayMillis) {
    if (delayMillis < 1000) return `${delayMillis}ms`
    const seconds = delayMillis / 1000
    return Number.isInteger(seconds) ? `${seconds}s` : `${seconds.toFixed(1)}s`
  }

  function appendStreamingText(event, payload) {
    if (!activeStreamId) {
      activeStreamId = `stream:${event.turnId}:active`
    }
    ensureStreamingMessage(event)
    const delta = payload.delta || ''
    if (!delta) return
    const message = messages.value.find((item) => item.messageId === activeStreamId)
    if (message) {
      message.content = `${message.content || ''}${delta}`
      message.thinking = false
    }
  }

  function appendStreamingReasoning(event, payload) {
    if (!activeStreamId) {
      activeStreamId = `stream:${event.turnId}:active`
    }
    ensureStreamingMessage(event)
    const delta = payload.delta || ''
    if (!delta) return
    const message = messages.value.find((item) => item.messageId === activeStreamId)
    if (message) {
      message.reasoningContent = `${message.reasoningContent || ''}${delta}`
      message.thinking = false
    }
  }

  function startThinkingMessage(event, payload) {
    clearEmptyThinkingMessage()
    activeStreamId = `stream:${event.turnId}:${payload.iteration || Date.now()}`
    messages.value.push({
      messageId: activeStreamId,
      sessionId: event.sessionId,
      role: 'assistant',
      content: '',
      reasoningContent: '',
      streaming: true,
      thinking: true,
      startedAt: Date.now()
    })
  }

  function ensureStreamingMessage(event) {
    const existing = messages.value.find((message) => message.messageId === activeStreamId)
    if (!existing) {
      messages.value.push({
        messageId: activeStreamId,
        sessionId: event.sessionId,
        role: 'assistant',
        content: '',
        reasoningContent: '',
        streaming: true,
        thinking: true,
        startedAt: Date.now()
      })
    }
  }

  function mergeMessage(message) {
    if (!message || !message.messageId) return
    if (message.role === 'assistant' && activeStreamId) {
      replaceStreamingMessage(message)
      return
    }
    if (message.role === 'tool' && replacePendingToolMessage(message)) {
      return
    }

    const existing = messages.value.findIndex((item) => item.messageId === message.messageId)
    if (existing >= 0) {
      messages.value.splice(existing, 1, message)
    } else {
      messages.value.push(message)
    }
  }

  function replaceStreamingMessage(message) {
    const index = messages.value.findIndex((item) => item.messageId === activeStreamId)
    if (index >= 0) {
      messages.value.splice(index, 1, message)
    } else {
      messages.value.push(message)
    }
    activeStreamId = null
  }

  function clearEmptyThinkingMessage() {
    if (!activeStreamId) return
    const index = messages.value.findIndex((message) => message.messageId === activeStreamId)
    if (index >= 0
        && !messages.value[index].content
        && !messages.value[index].reasoningContent) {
      messages.value.splice(index, 1)
    }
    activeStreamId = null
  }

  function failActiveStreamingMessage() {
    if (!activeStreamId) return
    const index = messages.value.findIndex((message) => message.messageId === activeStreamId)
    if (index < 0) {
      activeStreamId = null
      return
    }
    const message = messages.value[index]
    if (!message.content && !message.reasoningContent) {
      messages.value.splice(index, 1)
    } else {
      message.streaming = false
      message.thinking = false
      message.failed = true
    }
    activeStreamId = null
  }

  function startToolExecution(event, payload) {
    const toolCallId = payload.toolCallId || `${event.turnId}:${Date.now()}`
    const messageId = `tool-running:${toolCallId}`
    pendingToolMessages.set(toolCallId, messageId)
    messages.value.push({
      messageId,
      sessionId: event.sessionId,
      turnId: event.turnId,
      role: 'tool',
      toolCallId,
      toolName: payload.toolName || 'tool',
      argumentsJson: payload.arguments || '',
      content: '',
      running: true,
      startedAt: Date.now()
    })
  }

  function updateToolExecution(payload) {
    const message = pendingToolMessage(payload.toolCallId)
    if (!message) return
    message.progress = payload.message || payload.status || payload.text || ''
  }

  function completeToolExecution(payload) {
    const message = pendingToolMessage(payload.toolCallId)
    if (!message) return
    message.running = false
    message.completedAt = Date.now()
    message.content = payload.result || ''
    message.stopped = payload.stopped === true
    if (message.approval) {
      message.approval.pending = false
      message.approval.responding = false
    }
  }

  function requestToolApproval(event, payload) {
    const approval = {
      requestId: payload.requestId || activeRequestId,
      approvalId: payload.approvalId,
      title: payload.title || 'Approval required',
      message: payload.message || '',
      action: payload.action || '',
      target: payload.target || '',
      approved: null,
      pending: true,
      responding: false
    }
    const message = pendingToolMessage(payload.toolCallId)
    if (message) {
      message.approval = approval
      message.progress = approval.title
      return
    }
    messages.value.push({
      messageId: `approval:${approval.approvalId || Date.now()}`,
      sessionId: event.sessionId,
      role: 'status',
      content: `${approval.title}: ${approval.target || approval.action || 'tool execution'}`
    })
  }

  function resolveToolApprovalEvent(payload) {
    const message = pendingToolMessage(payload.toolCallId)
    if (!message || !message.approval) return
    message.approval.pending = false
    message.approval.responding = false
    message.approval.approved = payload.approved === true
    message.approval.reason = payload.reason || ''
    message.progress = message.approval.approved ? 'Approved' : 'Denied'
  }

  async function resolveToolApproval({ approvalId, requestId, approved }) {
    if (!approvalId) return
    const message = messages.value.find((item) => item.approval?.approvalId === approvalId)
    const approval = message ? message.approval : null
    if (approval) {
      approval.responding = true
    }
    try {
      await request('/api/chat/approvals/resolve', {
        method: 'POST',
        body: JSON.stringify({
          requestId: requestId || approval?.requestId || activeRequestId,
          approvalId,
          approved,
          reason: approved ? 'Approved by user' : 'Denied by user'
        })
      })
    } catch (error) {
      if (approval) {
        approval.responding = false
      }
      appendLocalErrorMessage(`Failed to resolve approval: ${error.message}`)
    }
  }

  function replacePendingToolMessage(message) {
    const pendingMessageId = pendingToolMessages.get(message.toolCallId)
    if (!pendingMessageId) return false
    const index = messages.value.findIndex((item) => item.messageId === pendingMessageId)
    if (index >= 0) {
      messages.value.splice(index, 1, message)
    } else {
      messages.value.push(message)
    }
    pendingToolMessages.delete(message.toolCallId)
    return true
  }

  function pendingToolMessage(toolCallId) {
    const messageId = pendingToolMessages.get(toolCallId)
    if (!messageId) return null
    return messages.value.find((message) => message.messageId === messageId) || null
  }

  function waitForBrowserPaint() {
    if (document.visibilityState !== 'visible') {
      return Promise.resolve()
    }
    return new Promise((resolve) => window.requestAnimationFrame(() => resolve()))
  }

  function completePendingTools(status) {
    for (const messageId of pendingToolMessages.values()) {
      const message = messages.value.find((item) => item.messageId === messageId)
      if (!message || !message.running) continue
      message.running = false
      message.completedAt = Date.now()
      message.stopped = status === 'stopped'
      message.failed = status === 'failed'
    }
  }

  function resetTransientRunState() {
    activeStreamId = null
    pendingToolMessages.clear()
  }

  function upsertCompactionMessage(event, payload, active) {
    const messageId = `compaction:${event.turnId || Date.now()}`
    const existing = messages.value.findIndex((message) => message.messageId === messageId)
    const message = {
      messageId,
      sessionId: event.sessionId,
      role: 'status',
      content: compactStatusText(payload, active),
      streaming: active
    }
    if (existing >= 0) {
      messages.value.splice(existing, 1, message)
    } else {
      messages.value.push(message)
    }
  }

  function compactStatusText(payload, active) {
    if (active) {
      return `Compacting context (${payload.estimatedTokens || '?'} / ${payload.thresholdTokens || '?'} estimated tokens)`
    }
    const before = payload.estimatedTokensBefore || '?'
    const after = payload.estimatedTokensAfter || '?'
    return `Context compacted (${before} -> ${after} estimated tokens)`
  }

  function appendCustomEventMessage(event, type, payload) {
    messages.value.push({
      messageId: `event:${event.eventId || `${type}:${Date.now()}`}`,
      sessionId: event.sessionId,
      role: 'status',
      content: customEventText(type, payload)
    })
  }

  function customEventText(type, payload) {
    const label = String(type || 'event')
    const text = payload.message || payload.status || payload.text || payload.title
    if (text) return `${label}: ${text}`
    if (Object.keys(payload).length === 0) return label
    return `${label}: ${JSON.stringify(payload)}`
  }

  function parsePayload(payloadJson) {
    if (!payloadJson) return {}
    try {
      return JSON.parse(payloadJson)
    } catch {
      return {}
    }
  }

  function firstVisibleSession() {
    for (const session of sessions.value) {
      if (!hiddenProjectKeys.value.includes(projectKey(sessionProjectName(session)))) {
        return session
      }
    }
    return null
  }

  function currentProjectLabel(workspacePath) {
    return currentSession.value ? projectAliases.value[currentProjectKey.value] || sessionProjectName(currentSession.value) : projectPathLabel(workspacePath)
  }

  function sessionProjectName(session) {
    if (!session) {
      return DEFAULT_PROJECT_NAME
    }
    if (session.projectName && String(session.projectName).trim()) {
      return normalizeProjectName(session.projectName)
    }
    if (session.workspacePath) {
      return normalizeProjectName(projectName(session.workspacePath))
    }
    return DEFAULT_PROJECT_NAME
  }

  function normalizeProjectName(name) {
    return String(name || '').replace(/\s+/g, ' ').trim()
  }

  function projectKey(name) {
    return normalizeProjectName(name).toLowerCase() || '__default__'
  }

  function hideProject(key) {
    if (!hiddenProjectKeys.value.includes(key)) {
      hiddenProjectKeys.value = [...hiddenProjectKeys.value, key]
      saveJson('jagent.hiddenProjects', hiddenProjectKeys.value)
    }
  }

  function showProject(key) {
    if (hiddenProjectKeys.value.includes(key)) {
      hiddenProjectKeys.value = hiddenProjectKeys.value.filter((item) => item !== key)
      saveJson('jagent.hiddenProjects', hiddenProjectKeys.value)
    }
  }

  function setApprovalMode(value) {
    approvalMode.value = value === 'full_access' ? 'full_access' : 'ask_approval'
    saveJson('jagent.approvalMode', approvalMode.value)
  }

  function shouldAutoNameChat(session, currentMessages) {
    if (!session || currentMessages.length > 0) return false
    const title = session.title || ''
    return title === 'New Session' || title.startsWith('New Chat')
  }

  async function renameChatFromPrompt(sessionId, prompt) {
    const title = chatTitleFromPrompt(prompt)
    if (!title) return
    try {
      const updated = await request('/api/sessions/rename', {
        method: 'POST',
        body: JSON.stringify({ sessionId, title })
      })
      if (currentSession.value && currentSession.value.sessionId === updated.sessionId) {
        currentSession.value = updated
      }
      await loadSessions()
    } catch (error) {
      console.warn('Failed to rename chat from prompt', error)
    }
  }

  function chatTitleFromPrompt(prompt) {
    const firstLine = String(prompt || '')
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find(Boolean) || ''
    const title = firstLine.replace(/\s+/g, ' ').trim()
    return title.length > 36 ? `${title.slice(0, 36)}...` : title
  }

  function clearStopFallback() {
    if (stopFallbackTimer) {
      window.clearTimeout(stopFallbackTimer)
      stopFallbackTimer = null
    }
  }

  return {
    sessions,
    currentSession,
    messages,
    tools,
    agentContext,
    provider,
    draft,
    projectNameDraft,
    projectWorkspaceDraft,
    projectError,
    renameTitleDraft,
    renameError,
    running,
    stopping,
    stopReady,
    approvalMode,
    projectDialogOpen,
    projectSubmitting,
    renameDialogOpen,
    renameSubmitting,
    providerLabel,
    statusText,
    workspaceProjectsEnabled,
    currentProjectKey,
    renameDialogTitle,
    renameDialogLabel,
    projectGroups,
    loadAgentContext,
    bootstrap,
    openProjectDialog,
    closeProjectDialog,
    submitProjectDialog,
    createSessionFromProject,
    removeProject,
    removeSession,
    openChatRenameDialog,
    openProjectRenameDialog,
    closeRenameDialog,
    submitRenameDialog,
    selectProject,
    selectSession,
    sendMessage,
    stopMessage,
    setApprovalMode,
    resolveToolApproval
  }
}
