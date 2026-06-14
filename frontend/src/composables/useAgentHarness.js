import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { request } from '../api/http'
import { loadJson, saveJson } from '../utils/storage'
import { projectName, projectPathLabel, workspaceKey } from '../utils/workspace'

const ignoredCoreEvents = new Set([
  'agent_start',
  'turn_start',
  'turn_end',
  'tool_execution_start',
  'tool_execution_update',
  'tool_execution_end'
])

export function useAgentHarness() {
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
  const projectPathDraft = ref('')
  const projectError = ref('')
  const renameTitleDraft = ref('')
  const renameError = ref('')
  const running = ref(false)
  const stopping = ref(false)
  const stopReady = ref(false)
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

  const providerLabel = computed(() => {
    if (!provider.value) return 'Loading runtime'
    return `${provider.value.model} via ${provider.value.activeProvider}`
  })

  const statusText = computed(() => {
    if (!currentSession.value) return 'Create or select a session to begin'
    const prefix = stopping.value
      ? 'Stopping agent run'
      : running.value && !stopReady.value
        ? 'Starting agent run'
      : running.value
        ? 'Agent loop is streaming events'
        : `${messages.value.length} messages`
    return `${prefix} | ${projectPathLabel(currentSession.value.workspacePath)}`
  })

  const currentProjectKey = computed(() => currentSession.value ? workspaceKey(currentSession.value.workspacePath) : '')
  const renameDialogTitle = computed(() => renameTargetType.value === 'project' ? 'Rename project' : 'Rename chat')
  const renameDialogLabel = computed(() => renameTargetType.value === 'project' ? 'Project name' : 'Chat title')

  const projectGroups = computed(() => {
    const groups = new Map()
    for (const session of sessions.value) {
      const key = workspaceKey(session.workspacePath)
      if (hiddenProjectKeys.value.includes(key)) continue
      if (!groups.has(key)) {
        groups.set(key, {
          key,
          workspacePath: session.workspacePath || '',
          name: projectAliases.value[key] || projectName(session.workspacePath),
          pathLabel: projectPathLabel(session.workspacePath),
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

  async function createSession(workspacePath = null) {
    const targetWorkspace = workspacePath || (currentSession.value && currentSession.value.workspacePath) || ''
    projectError.value = ''
    showProject(workspaceKey(targetWorkspace))
    const session = await request('/api/sessions', {
      method: 'POST',
      body: JSON.stringify({
        title: `New Chat - ${projectAliases.value[workspaceKey(targetWorkspace)] || projectName(targetWorkspace)}`,
        workspacePath: targetWorkspace
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

  function openProjectDialog() {
    projectError.value = ''
    projectPathDraft.value = ''
    projectDialogOpen.value = true
  }

  function closeProjectDialog() {
    if (projectSubmitting.value) return
    projectDialogOpen.value = false
    projectError.value = ''
  }

  async function submitProjectDialog() {
    const workspacePath = projectPathDraft.value.trim()
    if (!workspacePath) return
    projectError.value = ''
    projectSubmitting.value = true
    try {
      await createSession(workspacePath)
      projectDialogOpen.value = false
      projectPathDraft.value = ''
    } catch (error) {
      projectError.value = error.message
    } finally {
      projectSubmitting.value = false
    }
  }

  async function createSessionFromProject(project) {
    await createSession(project.workspacePath)
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
    activeStreamId = null
    activeRequestId = createRequestId()
    streamController = new AbortController()
    try {
      await streamRequest(
        '/api/chat/stream',
        { requestId: activeRequestId, sessionId, content },
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
      await request(`/api/chat/requests/${encodeURIComponent(activeRequestId)}/stop`, {
        method: 'POST'
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
    stopReady.value = true

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      buffer = consumeSseBuffer(buffer)
    }
    buffer += decoder.decode()
    consumeSseBuffer(`${buffer}\n\n`)
  }

  function consumeSseBuffer(buffer) {
    const normalized = buffer.replace(/\r\n/g, '\n')
    const blocks = normalized.split('\n\n')
    const rest = blocks.pop() || ''
    for (const block of blocks) {
      consumeSseBlock(block)
    }
    return rest
  }

  function consumeSseBlock(block) {
    const lines = block.split('\n')
    let eventName = 'message'
    const data = []
    for (const line of lines) {
      if (line.startsWith('event:')) eventName = line.slice(6).trim()
      if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
    }
    if (data.length === 0) return
    const event = JSON.parse(data.join('\n'))
    handleAgentEvent(eventName, event)
  }

  function replayTimelineEvents(events) {
    activeStreamId = null
    messages.value = []
    for (const event of events) {
      handleAgentEvent(event.type || 'message', event, { replay: true })
    }
    activeStreamId = null
  }

  function handleAgentEvent(eventName, event, options = {}) {
    const payload = parsePayload(event.payloadJson)
    const type = event.type || eventName
    if (type === 'message_start') {
      if (!options.replay) {
        activeStreamId = `stream:${event.turnId}:${payload.iteration || Date.now()}`
      }
    } else if (type === 'message_update') {
      if (!options.replay) {
        appendStreamingText(event, payload)
      }
    } else if (type === 'message_end') {
      mergeMessage(payload.message)
    } else if (type === 'compaction_start') {
      upsertCompactionMessage(event, payload, true)
    } else if (type === 'compaction_end') {
      upsertCompactionMessage(event, payload, false)
    } else if (type === 'agent_end') {
      running.value = false
    } else if (type === 'agent_stopped') {
      clearStopFallback()
      stopping.value = false
      stopReady.value = false
      running.value = false
      appendCustomEventMessage(event, type, payload)
    } else if (type === 'agent_error') {
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
    }
  }

  function ensureStreamingMessage(event) {
    const existing = messages.value.find((message) => message.messageId === activeStreamId)
    if (!existing) {
      messages.value.push({
        messageId: activeStreamId,
        sessionId: event.sessionId,
        role: 'assistant',
        content: '',
        streaming: true
      })
    }
  }

  function mergeMessage(message) {
    if (!message || !message.messageId) return
    if (message.role === 'assistant' && activeStreamId) {
      replaceStreamingMessage(message)
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
    if (label === 'agent_stopped') return 'Agent stopped'
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
      if (!hiddenProjectKeys.value.includes(workspaceKey(session.workspacePath))) {
        return session
      }
    }
    return null
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

  function createRequestId() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') {
      return window.crypto.randomUUID()
    }
    return `req_${Date.now()}_${Math.random().toString(16).slice(2)}`
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
    projectPathDraft,
    projectError,
    renameTitleDraft,
    renameError,
    running,
    stopping,
    stopReady,
    projectDialogOpen,
    projectSubmitting,
    renameDialogOpen,
    renameSubmitting,
    providerLabel,
    statusText,
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
    stopMessage
  }
}
