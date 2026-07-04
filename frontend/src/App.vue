<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="brand">
        <div>
          <h1>JAgentHarness</h1>
          <p>{{ providerLabel }}</p>
        </div>
      </div>

      <el-radio-group v-model="activeView" class="app-tabs" aria-label="Primary">
        <el-radio-button value="chat">Chat</el-radio-button>
        <el-radio-button value="prompts">Prompts</el-radio-button>
        <el-radio-button value="skills">Skills</el-radio-button>
        <el-radio-button v-if="mcpAvailable" value="mcp">MCP</el-radio-button>
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
          :approval-mode="approvalMode"
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

      <main v-else-if="activeView === 'prompts'" class="workspace management-shell">
        <KnowledgePanel
          key="prompts"
          mode="prompts"
          :session-id="currentSession ? currentSession.sessionId : ''"
          @changed="refreshAgentContext"
        />
      </main>

      <main v-else-if="activeView === 'skills'" class="workspace management-shell">
        <KnowledgePanel
          key="skills"
          mode="skills"
          :session-id="currentSession ? currentSession.sessionId : ''"
          @changed="refreshAgentContext"
        />
      </main>

      <main v-else class="workspace management-shell">
        <McpPanel
          :session-id="currentSession ? currentSession.sessionId : ''"
          @changed="refreshAgentContext"
        />
      </main>
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
import InspectorPanel from './components/InspectorPanel.vue'
import KnowledgePanel from './components/KnowledgePanel.vue'
import McpPanel from './components/McpPanel.vue'
import ProjectDialog from './components/ProjectDialog.vue'
import RenameDialog from './components/RenameDialog.vue'
import Sidebar from './components/Sidebar.vue'
import { useAgentHarness } from './composables/useAgentHarness'
import { request } from './api/http'

const activeView = ref('chat')
const mcpAvailable = ref(false)

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

async function refreshAgentContext() {
  await loadAgentContext(currentSession.value ? currentSession.value.sessionId : null)
}
</script>
