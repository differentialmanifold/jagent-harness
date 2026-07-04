import { computed, markRaw, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
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
  const sessionRuntimeStates = reactive(new Map())
  const emptySessionState = reactive(createSessionRuntimeState(''))
  const tools = ref([])
  const agentContext = ref({
    tools: [],
    promptFiles: [],
    skills: [],
    configRoot: '',
    workspaceRoot: ''
  })
  const provider = ref(null)
  const projectNameDraft = ref('')
  const projectWorkspaceDraft = ref('')
  const projectError = ref('')
  const renameTitleDraft = ref('')
  const renameError = ref('')
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
  let sessionSelectionVersion = 0

  const currentRuntimeState = computed(() => {
    const sessionId = currentSession.value?.sessionId
    return sessionId ? runtimeState(sessionId) : emptySessionState
  })
  const messages = computed(() => currentRuntimeState.value.messages)
  const draft = computed({
    get: () => currentRuntimeState.value.draft,
    set: (value) => {
      currentRuntimeState.value.draft = value
    }
  })
  const running = computed(() => currentRuntimeState.value.running)
  const stopping = computed(() => currentRuntimeState.value.stopping)
  const stopReady = computed(() => currentRuntimeState.value.stopReady)
  const anyRunning = computed(() => Array.from(sessionRuntimeStates.values()).some((state) => state.running))

  const providerLabel = computed(() => {
    if (!provider.value) return 'Loading runtime'
    return `${provider.value.model} via ${provider.value.activeProvider}`
  })

  const statusText = computed(() => {
    if (!currentSession.value) return 'Create or select a session to begin'
    const state = currentRuntimeState.value
    const pendingApproval = state.messages.find((message) => message.role === 'tool' && message.approval?.pending)
    const runningTool = state.messages.find((message) => message.role === 'tool' && message.running)
    const thinking = state.messages.find((message) => message.role === 'assistant' && message.thinking)
    const prefix = state.stopping
      ? 'Stopping agent run'
      : state.running && !state.stopReady
        ? 'Starting agent run'
      : pendingApproval
        ? `Waiting for ${pendingApproval.toolName || 'tool'} approval`
      : runningTool
        ? `Running ${runningTool.toolName || 'tool'}`
      : thinking
        ? 'Agent is thinking'
      : state.running
        ? 'Agent loop is streaming events'
        : `${state.messages.length} messages`
    return `${prefix} | ${currentProjectLabel(currentSession.value.workspacePath)}`
  })

  const currentProjectKey = computed(() => currentSession.value ? sessionProjectKey(currentSession.value) : '')
  const renameDialogTitle = computed(() => renameTargetType.value === 'project' ? 'Rename project' : 'Rename chat')
  const renameDialogLabel = computed(() => renameTargetType.value === 'project' ? 'Project name' : 'Chat title')

  const projectGroups = computed(() => {
    const groups = new Map()
    for (const session of sessions.value) {
      const sessionProjectNameValue = sessionProjectName(session)
      const key = sessionProjectKey(session)
      if (hiddenProjectKeys.value.includes(key)) continue
      if (!groups.has(key)) {
        groups.set(key, {
          key,
          projectId: session.projectId || '',
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
    for (const state of sessionRuntimeStates.values()) {
      clearStopFallback(state)
      if (state.streamController) {
        state.streamController.abort()
      }
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
    if (sessionId && currentSession.value?.sessionId !== sessionId) return
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
        projectName: targetProjectName,
        projectId: project ? (project.projectId || '') : (currentSession.value?.projectId || '')
      })
    })
    await loadSessions()
    await selectSession(session.sessionId)
  }

  async function refreshAfterDeletion(deletedSessionIds) {
    sessions.value = sessions.value.filter((session) => !deletedSessionIds.includes(session.sessionId))
    for (const sessionId of deletedSessionIds) {
      sessionRuntimeStates.delete(sessionId)
    }
    if (currentSession.value && deletedSessionIds.includes(currentSession.value.sessionId)) {
      currentSession.value = null
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
    if (anyRunning.value) return
    if (!window.confirm(`Remove project "${project.name}" from the sidebar? Chats will not be deleted.`)) return
    hideProject(project.key)
    if (currentProjectKey.value === project.key) {
      currentSession.value = null
      const first = firstVisibleSession()
      if (first) {
        await selectSession(first.sessionId)
      } else {
        await loadAgentContext()
      }
    }
  }

  async function removeSession(session) {
    if (runtimeState(session.sessionId).running) return
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
    if (runtimeState(session.sessionId).running) return
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
    const selectionVersion = ++sessionSelectionVersion
    const state = runtimeState(id)
    const details = await request('/api/sessions/detail', {
      method: 'POST',
      body: JSON.stringify({ sessionId: id })
    })
    if (selectionVersion !== sessionSelectionVersion) return
    currentSession.value = details.session
    if (!state.running) {
      replayTimelineEvents(details.events || [], state)
    }
    await loadAgentContext(id)
  }

  async function sendMessage() {
    if (!currentSession.value || !draft.value.trim()) return
    const content = draft.value
    const sessionId = currentSession.value.sessionId
    const state = runtimeState(sessionId)
    if (state.running) return
    if (shouldAutoNameChat(currentSession.value, state.messages)) {
      await renameChatFromPrompt(sessionId, content)
    }
    draft.value = ''
    state.running = true
    state.stopping = false
    state.stopReady = false
    state.stopRequested = false
    resetTransientRunState(state)
    state.activeRequestId = null
    state.streamController = markRaw(new AbortController())
    try {
      await streamRequest(
        '/api/chat/stream',
        { sessionId, content, approvalMode: approvalMode.value },
        state
      )
      await loadSessions()
    } catch (error) {
      if (!(error.name === 'AbortError' && state.stopRequested)) {
        appendLocalErrorMessage(state, error.message)
      }
    } finally {
      clearStopFallback(state)
      state.activeRequestId = null
      state.streamController = null
      state.stopRequested = false
      state.stopping = false
      state.stopReady = false
      state.running = false
    }
  }

  async function stopMessage() {
    const state = currentRuntimeState.value
    if (!state.running || state.stopping || !state.stopReady || !state.activeRequestId) return
    state.stopping = true
    state.stopRequested = true
    state.stopFallbackTimer = window.setTimeout(() => {
      if (state.streamController) {
        state.streamController.abort()
      }
    }, 3000)
    try {
      await request('/api/chat/requests/stop', {
        method: 'POST',
        body: JSON.stringify({ requestId: state.activeRequestId })
      })
    } catch (error) {
      clearStopFallback(state)
      state.stopRequested = false
      state.stopping = false
      appendLocalErrorMessage(state, `Failed to stop agent: ${error.message}`)
    }
  }

  async function streamRequest(url, body, state) {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: state.streamController.signal
    })
    if (!response.ok || !response.body) {
      const error = await response.json().catch(() => ({ message: response.statusText }))
      throw new Error(error.message || response.statusText)
    }
    state.activeRequestId = response.headers.get('X-Request-Id')
    if (!state.activeRequestId) {
      throw new Error('Chat stream response is missing X-Request-Id')
    }
    state.stopReady = true

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      buffer = await consumeSseBuffer(buffer, state)
    }
    buffer += decoder.decode()
    await consumeSseBuffer(`${buffer}\n\n`, state)
  }

  async function consumeSseBuffer(buffer, state) {
    const normalized = buffer.replace(/\r\n/g, '\n')
    const blocks = normalized.split('\n\n')
    const rest = blocks.pop() || ''
    for (const block of blocks) {
      await consumeSseBlock(block, state)
    }
    return rest
  }

  async function consumeSseBlock(block, state) {
    const lines = block.split('\n')
    let eventName = 'message'
    const data = []
    for (const line of lines) {
      if (line.startsWith('event:')) eventName = line.slice(6).trim()
      if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
    }
    if (data.length === 0) return
    const event = JSON.parse(data.join('\n'))
    if (event.sessionId && event.sessionId !== state.sessionId) return
    const payload = parsePayload(event.payloadJson)
    const type = event.type || eventName
    handleAgentEvent(eventName, event, state)
    if (currentSession.value?.sessionId === state.sessionId
        && (type === 'tool_execution_start'
          || (type === 'message_end' && payload.message?.role === 'assistant' && payload.message.toolCalls?.length))) {
      await waitForBrowserPaint()
    }
  }

  function replayTimelineEvents(events, state) {
    resetTransientRunState(state)
    state.messages.splice(0)
    for (const event of events) {
      handleAgentEvent(event.type || 'message', event, state, { replay: true })
    }
  }

  function handleAgentEvent(eventName, event, state, options = {}) {
    const payload = parsePayload(event.payloadJson)
    const type = event.type || eventName
    if (type === 'message_start') {
      if (!options.replay) {
        startThinkingMessage(event, payload, state)
      }
    } else if (type === 'message_update') {
      if (!options.replay) {
        appendStreamingText(event, payload, state)
      }
    } else if (type === 'message_reasoning_update') {
      if (!options.replay) {
        appendStreamingReasoning(event, payload, state)
      }
    } else if (type === 'message_end') {
      mergeMessage(payload.message, state)
    } else if (type === 'tool_execution_start') {
      startToolExecution(event, payload, state)
    } else if (type === 'tool_execution_update') {
      updateToolExecution(payload, state)
    } else if (type === 'tool_approval_requested') {
      requestToolApproval(event, payload, state)
    } else if (type === 'tool_approval_resolved') {
      resolveToolApprovalEvent(payload, state)
    } else if (type === 'tool_execution_end') {
      completeToolExecution(payload, state)
    } else if (type === 'compaction_start') {
      upsertCompactionMessage(event, payload, true, state)
    } else if (type === 'compaction_end') {
      upsertCompactionMessage(event, payload, false, state)
    } else if (type === 'model_retry') {
      if (!options.replay && payload.resetOutput) {
        discardActiveStreamingMessage(state)
      }
      appendModelRetryMessage(event, payload, state)
    } else if (type === 'agent_end') {
      clearEmptyThinkingMessage(state)
      completePendingTools('completed', state)
      if (options.replay) {
        resetTransientRunState(state)
      } else {
        state.running = false
      }
    } else if (type === 'agent_stopped') {
      clearStopFallback(state)
      clearEmptyThinkingMessage(state)
      completePendingTools('stopped', state)
      resetTransientRunState(state)
      if (!options.replay) {
        state.stopping = false
        state.stopReady = false
        state.running = false
      }
    } else if (type === 'agent_error') {
      failActiveStreamingMessage(state)
      completePendingTools('failed', state)
      resetTransientRunState(state)
      if (options.replay) {
        appendCustomEventMessage(event, type, payload, state)
      } else {
        throw new Error(payload.message || 'Agent stream failed')
      }
    } else if (!ignoredCoreEvents.has(type)) {
      appendCustomEventMessage(event, type, payload, state)
    }
  }

  function appendLocalErrorMessage(state, message) {
    state.messages.push({
      messageId: `local-error:${Date.now()}`,
      sessionId: state.sessionId,
      role: 'assistant',
      content: `Agent error: ${message || 'Unknown error'}`
    })
  }

  function appendModelRetryMessage(event, payload, state) {
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
    state.messages.push({
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

  function appendStreamingText(event, payload, state) {
    if (!state.activeStreamId) {
      state.activeStreamId = `stream:${event.turnId}:active`
    }
    ensureStreamingMessage(event, state)
    const delta = payload.delta || ''
    if (!delta) return
    const message = state.messages.find((item) => item.messageId === state.activeStreamId)
    if (message) {
      message.content = `${message.content || ''}${delta}`
      message.thinking = false
    }
  }

  function appendStreamingReasoning(event, payload, state) {
    if (!state.activeStreamId) {
      state.activeStreamId = `stream:${event.turnId}:active`
    }
    ensureStreamingMessage(event, state)
    const delta = payload.delta || ''
    if (!delta) return
    const message = state.messages.find((item) => item.messageId === state.activeStreamId)
    if (message) {
      message.reasoningContent = `${message.reasoningContent || ''}${delta}`
      message.thinking = false
    }
  }

  function startThinkingMessage(event, payload, state) {
    clearEmptyThinkingMessage(state)
    state.activeStreamId = `stream:${event.turnId}:${payload.iteration || Date.now()}`
    state.messages.push({
      messageId: state.activeStreamId,
      sessionId: event.sessionId,
      role: 'assistant',
      content: '',
      reasoningContent: '',
      streaming: true,
      thinking: true,
      startedAt: Date.now()
    })
  }

  function ensureStreamingMessage(event, state) {
    const existing = state.messages.find((message) => message.messageId === state.activeStreamId)
    if (!existing) {
      state.messages.push({
        messageId: state.activeStreamId,
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

  function mergeMessage(message, state) {
    if (!message || !message.messageId) return
    if (message.role === 'assistant' && state.activeStreamId) {
      replaceStreamingMessage(message, state)
      return
    }
    if (message.role === 'tool' && replacePendingToolMessage(message, state)) {
      return
    }

    const existing = state.messages.findIndex((item) => item.messageId === message.messageId)
    if (existing >= 0) {
      state.messages.splice(existing, 1, message)
    } else {
      state.messages.push(message)
    }
  }

  function replaceStreamingMessage(message, state) {
    const index = state.messages.findIndex((item) => item.messageId === state.activeStreamId)
    if (index >= 0) {
      state.messages.splice(index, 1, message)
    } else {
      state.messages.push(message)
    }
    state.activeStreamId = null
  }

  function clearEmptyThinkingMessage(state) {
    if (!state.activeStreamId) return
    const index = state.messages.findIndex((message) => message.messageId === state.activeStreamId)
    if (index >= 0
        && !state.messages[index].content
        && !state.messages[index].reasoningContent) {
      state.messages.splice(index, 1)
    }
    state.activeStreamId = null
  }

  function discardActiveStreamingMessage(state) {
    if (!state.activeStreamId) return
    const index = state.messages.findIndex((message) => message.messageId === state.activeStreamId)
    if (index >= 0) {
      state.messages.splice(index, 1)
    }
    state.activeStreamId = null
  }

  function failActiveStreamingMessage(state) {
    if (!state.activeStreamId) return
    const index = state.messages.findIndex((message) => message.messageId === state.activeStreamId)
    if (index < 0) {
      state.activeStreamId = null
      return
    }
    const message = state.messages[index]
    if (!message.content && !message.reasoningContent) {
      state.messages.splice(index, 1)
    } else {
      message.streaming = false
      message.thinking = false
      message.failed = true
    }
    state.activeStreamId = null
  }

  function startToolExecution(event, payload, state) {
    const toolCallId = payload.toolCallId || `${event.turnId}:${Date.now()}`
    const messageId = `tool-running:${toolCallId}`
    state.pendingToolMessages.set(toolCallId, messageId)
    state.messages.push({
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

  function updateToolExecution(payload, state) {
    const message = pendingToolMessage(payload.toolCallId, state)
    if (!message) return
    message.progress = payload.message || payload.status || payload.text || ''
  }

  function completeToolExecution(payload, state) {
    const message = pendingToolMessage(payload.toolCallId, state)
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

  function requestToolApproval(event, payload, state) {
    const approval = {
      requestId: payload.requestId || state.activeRequestId,
      approvalId: payload.approvalId,
      title: payload.title || 'Approval required',
      message: payload.message || '',
      action: payload.action || '',
      target: payload.target || '',
      approved: null,
      pending: true,
      responding: false
    }
    const message = pendingToolMessage(payload.toolCallId, state)
    if (message) {
      message.approval = approval
      message.progress = approval.title
      return
    }
    state.messages.push({
      messageId: `approval:${approval.approvalId || Date.now()}`,
      sessionId: event.sessionId,
      role: 'status',
      content: `${approval.title}: ${approval.target || approval.action || 'tool execution'}`
    })
  }

  function resolveToolApprovalEvent(payload, state) {
    const message = pendingToolMessage(payload.toolCallId, state)
    if (!message || !message.approval) return
    message.approval.pending = false
    message.approval.responding = false
    message.approval.approved = payload.approved === true
    message.approval.reason = payload.reason || ''
    message.progress = message.approval.approved ? 'Approved' : 'Denied'
  }

  async function resolveToolApproval({ approvalId, requestId, approved }) {
    if (!approvalId) return
    const state = currentRuntimeState.value
    const message = state.messages.find((item) => item.approval?.approvalId === approvalId)
    const approval = message ? message.approval : null
    if (approval) {
      approval.responding = true
    }
    try {
      await request('/api/chat/approvals/resolve', {
        method: 'POST',
        body: JSON.stringify({
          requestId: requestId || approval?.requestId || state.activeRequestId,
          approvalId,
          approved,
          reason: approved ? 'Approved by user' : 'Denied by user'
        })
      })
      if (approval) {
        approval.pending = false
        approval.responding = false
        approval.approved = approved === true
        approval.reason = approved ? 'Approved by user' : 'Denied by user'
      }
      if (message) {
        message.progress = approved ? 'Approved' : 'Denied'
      }
    } catch (error) {
      if (approval) {
        approval.responding = false
      }
      appendLocalErrorMessage(state, `Failed to resolve approval: ${error.message}`)
    }
  }

  function replacePendingToolMessage(message, state) {
    const pendingMessageId = state.pendingToolMessages.get(message.toolCallId)
    if (!pendingMessageId) return false
    const index = state.messages.findIndex((item) => item.messageId === pendingMessageId)
    if (index >= 0) {
      state.messages.splice(index, 1, message)
    } else {
      state.messages.push(message)
    }
    state.pendingToolMessages.delete(message.toolCallId)
    return true
  }

  function pendingToolMessage(toolCallId, state) {
    const messageId = state.pendingToolMessages.get(toolCallId)
    if (!messageId) return null
    return state.messages.find((message) => message.messageId === messageId) || null
  }

  function waitForBrowserPaint() {
    if (document.visibilityState !== 'visible') {
      return Promise.resolve()
    }
    return new Promise((resolve) => window.requestAnimationFrame(() => resolve()))
  }

  function completePendingTools(status, state) {
    for (const messageId of state.pendingToolMessages.values()) {
      const message = state.messages.find((item) => item.messageId === messageId)
      if (!message || !message.running) continue
      message.running = false
      message.completedAt = Date.now()
      message.stopped = status === 'stopped'
      message.failed = status === 'failed'
    }
  }

  function resetTransientRunState(state) {
    state.activeStreamId = null
    state.pendingToolMessages.clear()
  }

  function upsertCompactionMessage(event, payload, active, state) {
    const messageId = `compaction:${event.turnId || Date.now()}`
    const existing = state.messages.findIndex((message) => message.messageId === messageId)
    const message = {
      messageId,
      sessionId: event.sessionId,
      role: 'status',
      content: compactStatusText(payload, active),
      streaming: active
    }
    if (existing >= 0) {
      state.messages.splice(existing, 1, message)
    } else {
      state.messages.push(message)
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

  function appendCustomEventMessage(event, type, payload, state) {
    state.messages.push({
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
      if (!hiddenProjectKeys.value.includes(sessionProjectKey(session))) {
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

  function sessionProjectKey(session) {
    return session?.projectId || projectKey(sessionProjectName(session))
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

  function createSessionRuntimeState(sessionId) {
    return {
      sessionId,
      messages: [],
      draft: '',
      running: false,
      stopping: false,
      stopReady: false,
      stopRequested: false,
      activeStreamId: null,
      activeRequestId: null,
      streamController: null,
      stopFallbackTimer: null,
      pendingToolMessages: new Map()
    }
  }

  function runtimeState(sessionId) {
    if (!sessionRuntimeStates.has(sessionId)) {
      sessionRuntimeStates.set(sessionId, createSessionRuntimeState(sessionId))
    }
    return sessionRuntimeStates.get(sessionId)
  }

  function clearStopFallback(state) {
    if (state.stopFallbackTimer) {
      window.clearTimeout(state.stopFallbackTimer)
      state.stopFallbackTimer = null
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
    anyRunning,
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
