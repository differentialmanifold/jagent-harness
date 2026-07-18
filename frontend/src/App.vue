<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="brand">
        <div>
          <h1>JAgentHarness</h1>
          <p>{{ providerLabel }}</p>
        </div>
      </div>

      <el-radio-group :model-value="activeView" class="app-tabs" aria-label="Primary" @change="changeActiveView">
        <el-radio-button value="chat">Chat</el-radio-button>
        <el-radio-button value="configuration">Configuration</el-radio-button>
      </el-radio-group>
    </header>

    <div :class="['view-frame', activeView === 'chat' ? 'chat-frame' : 'management-frame']">
      <Sidebar
        v-if="activeView === 'chat'"
        :project-groups="projectGroups"
        :current-project-key="currentProjectKey"
        :current-session="currentSession"
        :running="anyRunning"
        @open-project-dialog="openProjectDialog"
        @select-project="selectProject"
        @create-session="createSessionFromProject"
        @rename-project="openProjectRenameDialog"
        @remove-project="removeProject"
        @select-session="selectSessionAndShowChat"
        @rename-chat="openChatRenameDialog"
        @remove-chat="removeSession"
      />

      <main v-if="activeView === 'chat'" class="workspace">
        <ChatPanel
          v-model:draft="draft"
          :current-session="currentSession"
          :messages="messages"
          :provider="provider"
          :status-text="statusText"
          :running="running"
          :stopping="stopping"
          :stop-ready="stopReady"
          :active-run-id="activeRunId"
          :pending-inputs="pendingInputs"
          :submitting-input="submittingInput"
          :message-revision="messageRevision"
          :approval-mode="approvalMode"
          :context-usage="contextUsage"
          @update:approval-mode="setApprovalMode"
          @send="sendMessage"
          @stop="stopMessage"
          @resolve-tool-approval="resolveToolApproval"
        />

        <InspectorPanel
          :provider="provider"
          :tools="tools"
          :agent-context="agentContext"
        />
      </main>

      <KeepAlive>
        <ConfigurationPanel
          v-if="activeView === 'configuration'"
          ref="configurationPanelRef"
          :project-groups="projectGroups"
          :current-project-key="currentProjectKey"
          :mcp-available="mcpAvailable"
          @changed="refreshAgentContext"
        />
      </KeepAlive>
    </div>

    <ProjectDialog
      v-model:project-name="projectNameDraft"
      v-model:workspace-path="projectWorkspaceDraft"
      :open="projectDialogOpen"
      :submitting="projectSubmitting"
      :error="projectError"
      :workspace-enabled="workspaceProjectsEnabled"
      @submit="submitProjectDialog"
      @close="closeProjectDialog"
    />

    <RenameDialog
      v-model="renameTitleDraft"
      :open="renameDialogOpen"
      :title="renameDialogTitle"
      :label="renameDialogLabel"
      :submitting="renameSubmitting"
      :error="renameError"
      @submit="submitRenameDialog"
      @close="closeRenameDialog"
    />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElRadioButton, ElRadioGroup } from 'element-plus'
import ChatPanel from './components/ChatPanel.vue'
import ConfigurationPanel from './components/ConfigurationPanel.vue'
import InspectorPanel from './components/InspectorPanel.vue'
import ProjectDialog from './components/ProjectDialog.vue'
import RenameDialog from './components/RenameDialog.vue'
import Sidebar from './components/Sidebar.vue'
import { useAgentHarness } from './composables/useAgentHarness'
import { request } from './api/http'

const activeView = ref('chat')
const mcpAvailable = ref(false)
const configurationPanelRef = ref(null)

onMounted(async () => {
  try {
    await request('/api/mcp/config')
    mcpAvailable.value = true
  } catch {
    mcpAvailable.value = false
  }
})

const {
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
} = useAgentHarness()

async function selectSessionAndShowChat(sessionId) {
  activeView.value = 'chat'
  await selectSession(sessionId)
}

async function changeActiveView(view) {
  if (view === activeView.value) return
  if (activeView.value === 'configuration') {
    const canLeave = await configurationPanelRef.value?.beforeLeave?.()
    if (canLeave === false) return
  }
  activeView.value = view
}

async function refreshAgentContext() {
  await loadAgentContext(currentSession.value ? currentSession.value.sessionId : null)
}
</script>
