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
        <el-radio-button value="chat">Projects</el-radio-button>
        <el-radio-button v-if="personalAvailable" value="personal">Agents</el-radio-button>
        <el-radio-button value="configuration">System Settings</el-radio-button>
      </el-radio-group>
    </header>

    <div :class="['view-frame', activeView === 'chat' ? 'chat-frame' : 'management-frame']">
      <Sidebar
        v-if="activeView === 'chat'"
        :project-groups="projectGroups"
        :current-project-key="currentProjectKey"
        :current-session="currentSession"
        :running="anyRunning"
        :owner-id="projectOwner"
        :agents="personalAgents"
        @select-owner="changeProjectOwner"
        @open-project-dialog="openProjectDialog"
        @select-project="selectProjectChecked"
        @create-session="createSessionFromProject"
        @rename-project="openProjectRenameDialog"
        @remove-project="removeProject"
        @select-session="selectSessionAndShowChat"
        @rename-chat="openChatRenameDialog"
        @remove-chat="removeSession"
      />

      <main v-if="activeView === 'chat'" class="workspace project-workspace">
        <nav class="project-view-tabs" aria-label="Project views">
          <el-radio-group :model-value="projectView" @change="changeProjectView">
            <el-radio-button value="chat">Chat</el-radio-button>
            <el-radio-button value="configuration">Configuration</el-radio-button>
          </el-radio-group>
          <span>{{ projectGroups.find(p => p.key === currentProjectKey)?.name || 'Choose a project' }}</span>
        </nav>
        <ChatPanel v-show="projectView === 'chat'"
          :active="projectView === 'chat'"
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
          :context-usage="contextUsage"
          :draft-images="draftImages"
          :adding-images="addingImages"
          :image-error="imageError"
          @add-images="addDraftImages"
          @remove-image="removeDraftImage"
          @send="sendMessage"
          @stop="stopMessage"
        />

        <ConfigurationPanel v-if="projectConfigurationVisited" v-show="projectView === 'configuration'" ref="projectConfigurationRef" mode="project"
          :project-groups="projectGroups" :current-project-key="currentProjectKey" :session-id="currentSession?.sessionId"
          :mcp-available="mcpAvailable" @changed="refreshAgentContext" @rename-project="openProjectRenameDialog" />
      </main>

      <KeepAlive>
        <PersonalAgentsPanel ref="personalPanelRef" v-if="activeView === 'personal'" @open-project="openAgentProject" />
      </KeepAlive>
      <KeepAlive>
        <ConfigurationPanel
          v-if="activeView === 'configuration'"
          ref="configurationPanelRef"
          mode="global"
          :project-groups="[]"
          :current-project-key="currentProjectKey"
          :mcp-available="mcpAvailable"
          @changed="refreshAgentContext"
        />
      </KeepAlive>
    </div>

    <ProjectDialog
      v-model:runner-id="projectRunnerDraft"
      :runners="runners"
      v-model:project-name="projectNameDraft"
      :open="projectDialogOpen"
      :submitting="projectSubmitting"
      :error="projectError"
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
import PersonalAgentsPanel from './components/PersonalAgentsPanel.vue'
import ChatPanel from './components/ChatPanel.vue'
import ConfigurationPanel from './components/ConfigurationPanel.vue'
import ProjectDialog from './components/ProjectDialog.vue'
import RenameDialog from './components/RenameDialog.vue'
import Sidebar from './components/Sidebar.vue'
import { useAgentHarness } from './composables/useAgentHarness'
import { request } from './api/http'

const activeView = ref('chat')
const projectConfigurationVisited = ref(false)
const projectView = ref('chat'), projectConfigurationRef = ref(null), personalPanelRef = ref(null)
async function changeProjectView(view) {
  if (projectView.value === 'configuration' && await projectConfigurationRef.value?.beforeLeave?.() === false) return
  if (view === 'configuration') projectConfigurationVisited.value = true
  projectView.value = view
}
const mcpAvailable = ref(false)
const personalAvailable = ref(false)
const personalAgents = ref([])
async function loadPersonalAgents() {
  try { personalAgents.value = await request('/api/v1/personal/agents'); personalAvailable.value = true } catch {}
}
const configurationPanelRef = ref(null)

onMounted(async () => {
  await loadPersonalAgents()
  try {
    await request('/api/v1/mcp/config')
    mcpAvailable.value = true
  } catch {
    mcpAvailable.value = false
  }
})

const {
  projectOwner,
  setProjectOwner,
  currentSession,
  messages,
  tools,
  agentContext,
  provider,
  draft,
  draftImages,
  addingImages,
  imageError,
  runners,
  projectRunnerDraft,
  projectNameDraft,
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
  loadSessions,
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
  addDraftImages,
  removeDraftImage,
  sendMessage,
  stopMessage
} = useAgentHarness()

async function beforeProjectChange() {
  return projectView.value !== 'configuration' || await projectConfigurationRef.value?.beforeLeave?.() !== false
}
async function selectProjectChecked(project) {
  if (await beforeProjectChange()) await selectProject(project)
}
async function changeProjectOwner(owner) {
  if (await beforeProjectChange()) await setProjectOwner(owner)
}
async function selectSessionAndShowChat(sessionId) {
  if (!await beforeProjectChange()) return
  activeView.value = 'chat'
  projectView.value = 'chat'
  await selectSession(sessionId)
}

async function changeActiveView(view) {
  if (view === activeView.value) return
  if (activeView.value === 'personal' && await personalPanelRef.value?.beforeLeave?.() === false) return
  if (activeView.value === 'chat' && projectView.value === 'configuration' && await projectConfigurationRef.value?.beforeLeave?.() === false) return
  if (activeView.value === 'configuration') {
    const canLeave = await configurationPanelRef.value?.beforeLeave?.()
    if (canLeave === false) return
  }
  activeView.value = view
  if (view === 'chat') { await loadPersonalAgents(); await loadSessions() }
}

async function openAgentProject(sessionId, ownerAgentId) {
  await loadPersonalAgents()
  await setProjectOwner(ownerAgentId)
  await selectSessionAndShowChat(sessionId)
}

async function refreshAgentContext() {
  await loadAgentContext(currentSession.value ? currentSession.value.sessionId : null)
}
</script>

<style scoped>
.project-workspace { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto minmax(0, 1fr); }
.project-view-tabs { display: flex; align-items: center; gap: 20px; padding: 10px 22px; border-bottom: 1px solid var(--el-border-color); background: #fff; }
.project-view-tabs span { font-size: 13px; color: var(--el-text-color-secondary); }

</style>
