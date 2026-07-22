import { computed, markRaw, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { request } from '../api/http'
import { loadJson, saveJson } from '../utils/storage'
import { projectName, projectPathLabel } from '../utils/workspace'

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
  const contextUsage = computed(() => currentRuntimeState.value.contextUsage)
  const activeRunId = computed(() => currentRuntimeState.value.activeRunId)
  const pendingInputs = computed(() => currentRuntimeState.value.pendingInputs)
  const submittingInput = computed(() => currentRuntimeState.value.submittingInput)
  const messageRevision = computed(() => currentRuntimeState.value.messageRevision)
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
    const pendingCount = state.pendingInputs.length
    const prefix = state.stopping
      ? 'Stopping agent run'
      : state.finishing
        ? 'Finishing agent run'
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
    const pendingLabel = pendingCount > 0 ? ` | ${pendingCount} queued` : ''
    return `${prefix}${pendingLabel} | ${currentProjectLabel(currentSession.value.workspacePath)}`
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
      cancelDeltaFlush(state)
      cancelRuntimeInputSubmission(state)
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
    state.contextUsage = normalizeUsage(details.latestUsage)
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
    if (state.running) {
      await submitRuntimeInput(content, state)
      return
    }
    if (shouldAutoNameChat(currentSession.value, state.messages)) {
      await renameChatFromPrompt(sessionId, content)
    }
    draft.value = ''
    state.running = true
    state.stopping = false
    state.stopReady = false
    state.stopRequested = false
    state.finishing = false
    resetTransientRunState(state)
    state.activeRunId = null
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
        appendLocalErrorMessage(state, error.message, error.runDurationMillis)
      }
    } finally {
      clearStopFallback(state)
      cancelRuntimeInputSubmission(state)
      state.pendingInputs.splice(0)
      state.streamController = null
      state.stopRequested = false
      state.stopping = false
      state.stopReady = false
      state.running = false
      state.finishing = false
      state.activeRunId = null
      state.runStartTimes.clear()
    }
  }

  async function submitRuntimeInput(content, state) {
    if (!state.running
        || state.submittingInput
        || state.stopping
        || !state.stopReady
        || !state.activeRunId) return
    const runId = state.activeRunId
    const controller = markRaw(new AbortController())

    const inputId = createInputId()
    const input = {
      inputId,
      content,
      status: 'submitting',
      createdAt: new Date().toISOString(),
      sequence: ++state.inputSequence
    }
    state.pendingInputs.push(input)
    state.submittingInput = true
    state.runtimeInputController = controller
    state.runtimeInputId = inputId
    state.draft = ''

    try {
      const receipt = await request(`/api/chat/runs/${encodeURIComponent(runId)}/messages`, {
        method: 'POST',
        body: JSON.stringify({ content, inputId }),
        signal: controller.signal
      })
      if (state.runtimeInputController !== controller || state.activeRunId !== runId) return
      const pending = state.pendingInputs.find((item) => item.inputId === inputId)
      if (pending) {
        Object.assign(pending, normalizePendingInput(receipt, input))
      }
    } catch (error) {
      if (state.appliedRuntimeInputId === inputId) return
      if (error.name === 'AbortError' || state.runtimeInputController !== controller) return
      removePendingInputs(state, [inputId])
      if (!state.draft.trim()) {
        state.draft = content
      }
      appendLocalErrorMessage(state, `Failed to submit message: ${error.message}`)
    } finally {
      if (state.runtimeInputController === controller) {
        state.runtimeInputController = null
        state.submittingInput = false
      }
      if (state.runtimeInputId === inputId) {
        state.runtimeInputId = null
      }
      if (state.appliedRuntimeInputId === inputId) {
        state.appliedRuntimeInputId = null
      }
    }
  }

  async function stopMessage() {
    const state = currentRuntimeState.value
    if (!state.running || state.stopping || !state.stopReady || !state.activeRunId) return
    const runId = state.activeRunId
    state.stopping = true
    state.stopRequested = true
    cancelRuntimeInputSubmission(state)
    state.stopFallbackTimer = window.setTimeout(() => {
      if (state.streamController) {
        state.streamController.abort()
      }
    }, 3000)
    try {
      await request(`/api/chat/runs/${encodeURIComponent(runId)}/stop`, {
        method: 'POST'
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
    state.activeRunId = response.headers.get('X-Run-Id')
    if (!state.activeRunId) {
      throw new Error('Chat stream response is missing X-Run-Id')
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
    cancelDeltaFlush(state)
    state.activeRunId = null
    state.activeTurnId = null
    state.turns.clear()
    state.runStartTimes.clear()
    state.pendingInputs.splice(0)
    state.replaying = true
    state.messages.splice(0)
    try {
      for (const event of events) {
        handleAgentEvent(event.type || 'message', event, state, { replay: true })
      }
    } finally {
      state.replaying = false
      state.activeRunId = null
      state.activeTurnId = null
      state.runStartTimes.clear()
      state.running = false
      state.stopReady = false
      state.finishing = false
      markMessagesChanged(state)
    }
  }

  function handleAgentEvent(eventName, event, state, options = {}) {
    const payload = parsePayload(event.payloadJson)
    const type = event.type || eventName
    if (type === 'agent_start') {
      startRun(event, payload, state)
    } else if (type === 'turn_start') {
      startTurn(event, payload, state)
    } else if (type === 'turn_end') {
      completeTurn(event, payload, state)
    } else if (type === 'run_input_batch_applied') {
      applyRunInputBatch(payload, state)
    } else if (type === 'message_start') {
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
    } else if (type === 'context_usage') {
      updateContextUsage(payload, state)
    } else if (type === 'agent_end') {
      clearEmptyThinkingMessage(state)
      completePendingTools('completed', state)
      completeRunTiming(event, payload, state)
      finishRun(event, payload, state)
      if (options.replay) {
        resetTransientRunState(state)
      } else {
        cancelRuntimeInputSubmission(state)
        state.pendingInputs.splice(0)
        state.stopReady = false
        state.stopping = false
        state.finishing = true
        // Keep the composer closed until EOF confirms that run resources and the stream have
        // finished closing, even though agent_end has already made the run itself terminal.
        state.running = true
      }
    } else if (type === 'agent_stopped') {
      clearStopFallback(state)
      cancelRuntimeInputSubmission(state)
      clearEmptyThinkingMessage(state)
      completePendingTools('stopped', state)
      completeRunTiming(event, payload, state)
      resetTransientRunState(state)
      state.pendingInputs.splice(0)
      state.activeRunId = null
      state.finishing = false
      finishActiveTurn('stopped', state)
      if (!options.replay) {
        state.stopping = false
        state.stopReady = false
        state.running = false
      }
    } else if (type === 'agent_error') {
      cancelRuntimeInputSubmission(state)
      failActiveStreamingMessage(state)
      completePendingTools('failed', state)
      const runDurationMillis = completeRunTiming(event, payload, state, { attach: false })
      resetTransientRunState(state)
      state.pendingInputs.splice(0)
      state.activeRunId = null
      state.finishing = false
      finishActiveTurn('failed', state)
      if (options.replay) {
        appendCustomEventMessage(event, type, payload, state, runDurationMillis)
      } else {
        const error = new Error(payload.message || payload.error || 'Agent stream failed')
        error.runDurationMillis = runDurationMillis
        throw error
      }
    } else {
      appendCustomEventMessage(event, type, payload, state)
    }
  }

  function startRun(event, payload, state) {
    state.activeRunId = eventRunId(event, payload)
    const startedAt = eventTime(event)
    if (state.activeRunId && startedAt !== null) {
      state.runStartTimes.set(state.activeRunId, startedAt)
    }
    state.running = true
    state.finishing = false
    if (state.activeRunId) {
      state.stopReady = true
    }
  }

  function finishRun(event, payload, state) {
    const runId = eventRunId(event, payload)
    if (!runId || runId === state.activeRunId) {
      state.activeRunId = null
    }
    finishActiveTurn('completed', state)
  }

  function completeRunTiming(event, payload, state, options = {}) {
    const runId = eventRunId(event, payload) || state.activeRunId
    if (!runId) return null
    const startedAt = state.runStartTimes.get(runId)
    state.runStartTimes.delete(runId)
    const endedAt = eventTime(event)
    if (startedAt === undefined || endedAt === null) return null
    const durationMillis = Math.max(0, endedAt - startedAt)
    if (options.attach === false) return durationMillis

    let message = null
    for (let index = state.messages.length - 1; index >= 0; index--) {
      const candidate = state.messages[index]
      if (candidate.role === 'assistant' && candidate.runId === runId) {
        message = candidate
        break
      }
    }
    if (!message) return durationMillis
    message.runDurationMillis = durationMillis
    markMessagesChanged(state)
    return durationMillis
  }

  function eventTime(event) {
    const timestamp = Date.parse(event?.createdAt)
    return Number.isFinite(timestamp) ? timestamp : null
  }

  function startTurn(event, payload, state) {
    const turnId = eventTurnId(event, payload)
    if (!turnId) return
    const existing = state.turns.get(turnId)
    const sequence = Number(payload.turnIndex || payload.sequence)
    state.turns.set(turnId, {
      ...(existing || {}),
      turnId,
      runId: eventRunId(event, payload),
      sequence: Number.isFinite(sequence) && sequence > 0 ? sequence : ++state.turnSequence,
      status: 'running',
      startedAt: payload.startedAt || event.createdAt || new Date().toISOString(),
      endedAt: null
    })
    state.activeTurnId = turnId
    pruneTurns(state)
  }

  function completeTurn(event, payload, state) {
    const turnId = eventTurnId(event, payload)
    if (!turnId) return
    const existing = state.turns.get(turnId) || {
      turnId,
      runId: eventRunId(event, payload),
      sequence: ++state.turnSequence
    }
    state.turns.set(turnId, {
      ...existing,
      status: payload.status || 'completed',
      endedAt: payload.endedAt || event.createdAt || new Date().toISOString()
    })
    if (state.activeTurnId === turnId) {
      state.activeTurnId = null
    }
    pruneTurns(state)
  }

  function finishActiveTurn(status, state) {
    if (!state.activeTurnId) return
    const turn = state.turns.get(state.activeTurnId)
    if (turn) {
      state.turns.set(state.activeTurnId, {
        ...turn,
        status,
        endedAt: new Date().toISOString()
      })
    }
    state.activeTurnId = null
  }

  function pruneTurns(state) {
    while (state.turns.size > 24) {
      state.turns.delete(state.turns.keys().next().value)
    }
  }

  function applyRunInputBatch(payload, state) {
    const inputIds = payload.inputIds || []
    removePendingInputs(state, inputIds)
    if (state.runtimeInputId && inputIds.includes(state.runtimeInputId)) {
      state.appliedRuntimeInputId = state.runtimeInputId
      cancelRuntimeInputSubmission(state)
    }
  }

  function normalizePendingInput(source, defaults = {}) {
    const value = source || {}
    return {
      inputId: value.inputId || defaults.inputId || '',
      content: value.content ?? defaults.content ?? '',
      status: String(value.status || defaults.status || 'pending').toLowerCase(),
      sequence: Number(value.sequence ?? defaults.sequence) || 0,
      createdAt: value.createdAt || defaults.createdAt || new Date().toISOString()
    }
  }

  function removePendingInputs(state, inputIds) {
    const ids = new Set((inputIds || []).filter(Boolean))
    if (ids.size === 0) return
    for (let index = state.pendingInputs.length - 1; index >= 0; index--) {
      if (ids.has(state.pendingInputs[index].inputId)) {
        state.pendingInputs.splice(index, 1)
      }
    }
  }

  function eventRunId(event, payload) {
    return event.runId || payload.runId || ''
  }

  function eventTurnId(event, payload) {
    return event.turnId || payload.turnId || ''
  }

  function createInputId() {
    if (globalThis.crypto?.randomUUID) {
      return `input_${globalThis.crypto.randomUUID()}`
    }
    return `input_${Date.now()}_${Math.random().toString(36).slice(2)}`
  }

  function activateTurnStream(event, payload, state) {
    const turnId = eventTurnId(event, payload)
    if (!turnId) return
    const streamId = `stream:${turnId}`
    if (state.activeStreamId && state.activeStreamId !== streamId) {
      flushStreamingDeltas(state)
    }
    state.activeStreamId = streamId
  }

  function scheduleDeltaFlush(state) {
    if (state.deltaFlushHandle !== null) return
    const flush = () => {
      state.deltaFlushHandle = null
      state.deltaFlushKind = null
      applyStreamingDeltas(state)
    }
    if (document.visibilityState === 'visible') {
      state.deltaFlushKind = 'animation'
      state.deltaFlushHandle = window.requestAnimationFrame(flush)
    } else {
      state.deltaFlushKind = 'timeout'
      state.deltaFlushHandle = window.setTimeout(flush, 16)
    }
  }

  function flushStreamingDeltas(state) {
    cancelScheduledDeltaFlush(state)
    applyStreamingDeltas(state)
  }

  function applyStreamingDeltas(state) {
    const contentDelta = state.pendingContentDelta
    const reasoningDelta = state.pendingReasoningDelta
    state.pendingContentDelta = ''
    state.pendingReasoningDelta = ''
    if ((!contentDelta && !reasoningDelta) || !state.activeStreamId) return
    const message = state.messages.find((item) => item.messageId === state.activeStreamId)
    if (!message) return
    if (contentDelta) {
      message.content = `${message.content || ''}${contentDelta}`
    }
    if (reasoningDelta) {
      message.reasoningContent = `${message.reasoningContent || ''}${reasoningDelta}`
    }
    message.thinking = false
    markMessagesChanged(state)
  }

  function cancelDeltaFlush(state) {
    cancelScheduledDeltaFlush(state)
    state.pendingContentDelta = ''
    state.pendingReasoningDelta = ''
  }

  function cancelScheduledDeltaFlush(state) {
    if (state.deltaFlushHandle !== null) {
      if (state.deltaFlushKind === 'animation') {
        window.cancelAnimationFrame(state.deltaFlushHandle)
      } else {
        window.clearTimeout(state.deltaFlushHandle)
      }
    }
    state.deltaFlushHandle = null
    state.deltaFlushKind = null
  }

  function markMessagesChanged(state) {
    if (!state.replaying) {
      state.messageRevision += 1
    }
  }

  function appendLocalErrorMessage(state, message, runDurationMillis) {
    const errorMessage = {
      messageId: `local-error:${Date.now()}`,
      sessionId: state.sessionId,
      role: 'assistant',
      content: `Agent error: ${message || 'Unknown error'}`,
      failed: true
    }
    if (Number.isFinite(runDurationMillis)) {
      errorMessage.runDurationMillis = runDurationMillis
    }
    state.messages.push(errorMessage)
    markMessagesChanged(state)
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
    markMessagesChanged(state)
  }

  function formatDelay(delayMillis) {
    if (delayMillis < 1000) return `${delayMillis}ms`
    const seconds = delayMillis / 1000
    return Number.isInteger(seconds) ? `${seconds}s` : `${seconds.toFixed(1)}s`
  }

  function appendStreamingText(event, payload, state) {
    activateTurnStream(event, payload, state)
    if (!state.activeStreamId) return
    ensureStreamingMessage(event, state)
    const delta = payload.delta || ''
    if (!delta) return
    state.pendingContentDelta += delta
    scheduleDeltaFlush(state)
  }

  function appendStreamingReasoning(event, payload, state) {
    activateTurnStream(event, payload, state)
    if (!state.activeStreamId) return
    ensureStreamingMessage(event, state)
    const delta = payload.delta || ''
    if (!delta) return
    state.pendingReasoningDelta += delta
    scheduleDeltaFlush(state)
  }

  function startThinkingMessage(event, payload, state) {
    const turnId = eventTurnId(event, payload)
    if (state.activeStreamId && state.activeStreamId !== `stream:${turnId}`) {
      clearEmptyThinkingMessage(state)
    }
    activateTurnStream(event, payload, state)
    if (!state.activeStreamId) return
    const existing = state.messages.find((message) => message.messageId === state.activeStreamId)
    if (existing) {
      existing.streaming = true
      existing.thinking = true
      markMessagesChanged(state)
      return
    }
    state.messages.push({
      messageId: state.activeStreamId,
      sessionId: event.sessionId,
      runId: eventRunId(event, payload),
      turnId: eventTurnId(event, payload),
      role: 'assistant',
      content: '',
      reasoningContent: '',
      streaming: true,
      thinking: true,
      startedAt: Date.now()
    })
    markMessagesChanged(state)
  }

  function ensureStreamingMessage(event, state) {
    const existing = state.messages.find((message) => message.messageId === state.activeStreamId)
    if (!existing) {
      state.messages.push({
        messageId: state.activeStreamId,
        sessionId: event.sessionId,
        runId: event.runId || state.activeRunId,
        turnId: event.turnId || state.activeTurnId,
        role: 'assistant',
        content: '',
        reasoningContent: '',
        streaming: true,
        thinking: true,
        startedAt: Date.now()
      })
      markMessagesChanged(state)
    }
  }

  function mergeMessage(message, state) {
    if (!message || !message.messageId) return
    if (message.role === 'assistant' && state.activeStreamId) {
      cancelDeltaFlush(state)
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
    markMessagesChanged(state)
  }

  function replaceStreamingMessage(message, state) {
    const index = state.messages.findIndex((item) => item.messageId === state.activeStreamId)
    if (index >= 0) {
      state.messages.splice(index, 1, message)
    } else {
      state.messages.push(message)
    }
    state.activeStreamId = null
    markMessagesChanged(state)
  }

  function clearEmptyThinkingMessage(state) {
    if (!state.activeStreamId) return
    flushStreamingDeltas(state)
    const index = state.messages.findIndex((message) => message.messageId === state.activeStreamId)
    if (index >= 0
        && !state.messages[index].content
        && !state.messages[index].reasoningContent) {
      state.messages.splice(index, 1)
      markMessagesChanged(state)
    }
    state.activeStreamId = null
  }

  function discardActiveStreamingMessage(state) {
    if (!state.activeStreamId) return
    const index = state.messages.findIndex((message) => message.messageId === state.activeStreamId)
    if (index >= 0) {
      state.messages.splice(index, 1)
      markMessagesChanged(state)
    }
    cancelDeltaFlush(state)
    state.activeStreamId = null
  }

  function failActiveStreamingMessage(state) {
    if (!state.activeStreamId) return
    flushStreamingDeltas(state)
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
    cancelDeltaFlush(state)
    state.activeStreamId = null
    markMessagesChanged(state)
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
    markMessagesChanged(state)
  }

  function updateToolExecution(payload, state) {
    const message = pendingToolMessage(payload.toolCallId, state)
    if (!message) return
    message.progress = payload.message || payload.status || payload.text || ''
    markMessagesChanged(state)
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
    markMessagesChanged(state)
  }

  function requestToolApproval(event, payload, state) {
    const approval = {
      runId: payload.runId || eventRunId(event, payload) || state.activeRunId,
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
      markMessagesChanged(state)
      return
    }
    state.messages.push({
      messageId: `approval:${approval.approvalId || Date.now()}`,
      sessionId: event.sessionId,
      role: 'status',
      content: `${approval.title}: ${approval.target || approval.action || 'tool execution'}`
    })
    markMessagesChanged(state)
  }

  function resolveToolApprovalEvent(payload, state) {
    const message = pendingToolMessage(payload.toolCallId, state)
    if (!message || !message.approval) return
    message.approval.pending = false
    message.approval.responding = false
    message.approval.approved = payload.approved === true
    message.approval.reason = payload.reason || ''
    message.progress = message.approval.approved ? 'Approved' : 'Denied'
    markMessagesChanged(state)
  }

  async function resolveToolApproval({ approvalId, runId, approved }) {
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
          runId: runId || approval?.runId || state.activeRunId,
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
      markMessagesChanged(state)
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
    markMessagesChanged(state)
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
    let changed = false
    for (const messageId of state.pendingToolMessages.values()) {
      const message = state.messages.find((item) => item.messageId === messageId)
      if (!message || !message.running) continue
      message.running = false
      message.completedAt = Date.now()
      message.stopped = status === 'stopped'
      message.failed = status === 'failed'
      changed = true
    }
    if (changed) markMessagesChanged(state)
  }

  function resetTransientRunState(state) {
    cancelDeltaFlush(state)
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
    markMessagesChanged(state)
  }

  function compactStatusText(payload, active) {
    if (active) {
      return `Compacting context (${payload.estimatedTokens || '?'} / ${payload.thresholdTokens || '?'} estimated tokens)`
    }
    const before = payload.estimatedTokensBefore || '?'
    const after = payload.estimatedTokensAfter || '?'
    return `Context compacted (${before} -> ${after} estimated tokens)`
  }

  function appendCustomEventMessage(event, type, payload, state, runDurationMillis) {
    const message = {
      messageId: `event:${event.eventId || `${type}:${Date.now()}`}`,
      sessionId: event.sessionId,
      role: 'status',
      content: customEventText(type, payload)
    }
    if (Number.isFinite(runDurationMillis)) {
      message.runDurationMillis = runDurationMillis
      message.failed = type === 'agent_error'
    }
    state.messages.push(message)
    markMessagesChanged(state)
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

  function updateContextUsage(payload, state) {
    state.contextUsage = normalizeUsage(payload)
  }

  function normalizeUsage(usage) {
    if (!usage) return null
    const contextWindowTokens = numberOrNull(usage.contextWindowTokens)
    const usedTokens = numberOrNull(usage.actualContextTokens) || numberOrNull(usage.estimatedTokens)
    if (!contextWindowTokens || !usedTokens) return null
    return {
      usageId: usage.usageId || '',
      messageId: usage.messageId || '',
      provider: usage.provider || '',
      model: usage.model || '',
      contextWindowTokens,
      thresholdTokens: numberOrNull(usage.thresholdTokens),
      estimateSource: usage.estimateSource || '',
      estimatedTokens: numberOrNull(usage.estimatedTokens),
      actualContextTokens: numberOrNull(usage.actualContextTokens),
      promptTokens: numberOrNull(usage.promptTokens),
      completionTokens: numberOrNull(usage.completionTokens),
      reasoningTokens: numberOrNull(usage.reasoningTokens),
      cachedTokens: numberOrNull(usage.cachedTokens),
      totalTokens: numberOrNull(usage.totalTokens),
      createdAt: usage.createdAt || ''
    }
  }

  function numberOrNull(value) {
    const number = Number(value)
    return Number.isFinite(number) && number > 0 ? number : null
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
      finishing: false,
      activeRunId: null,
      activeTurnId: null,
      activeStreamId: null,
      streamController: null,
      runtimeInputController: null,
      runtimeInputId: null,
      appliedRuntimeInputId: null,
      stopFallbackTimer: null,
      contextUsage: null,
      pendingToolMessages: new Map(),
      runStartTimes: markRaw(new Map()),
      turns: new Map(),
      turnSequence: 0,
      pendingInputs: [],
      inputSequence: 0,
      submittingInput: false,
      pendingContentDelta: '',
      pendingReasoningDelta: '',
      deltaFlushHandle: null,
      deltaFlushKind: null,
      replaying: false,
      messageRevision: 0
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

  function cancelRuntimeInputSubmission(state) {
    const controller = state.runtimeInputController
    state.runtimeInputController = null
    state.runtimeInputId = null
    state.submittingInput = false
    if (controller) {
      controller.abort()
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
    contextUsage,
    activeRunId,
    pendingInputs,
    submittingInput,
    messageRevision,
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
