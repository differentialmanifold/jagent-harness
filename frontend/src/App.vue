<template>
  <div class="app-shell">
    <Sidebar
      :provider-label="providerLabel"
      :project-groups="projectGroups"
      :current-project-key="currentProjectKey"
      :current-session="currentSession"
      :running="running"
      @refresh="bootstrap"
      @open-project-dialog="openProjectDialog"
      @select-project="selectProject"
      @create-session="createSessionFromProject"
      @rename-project="openProjectRenameDialog"
      @remove-project="removeProject"
      @select-session="selectSession"
      @rename-chat="openChatRenameDialog"
      @remove-chat="removeSession"
    />

    <main class="workspace">
      <ChatPanel
        v-model:draft="draft"
        :current-session="currentSession"
        :messages="messages"
        :provider="provider"
        :status-text="statusText"
        :running="running"
        @send="sendMessage"
      />

      <InspectorPanel
        :provider="provider"
        :tools="tools"
        :agent-context="agentContext"
      />
    </main>

    <ProjectDialog
      v-model="projectPathDraft"
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
import ChatPanel from './components/ChatPanel.vue'
import InspectorPanel from './components/InspectorPanel.vue'
import ProjectDialog from './components/ProjectDialog.vue'
import RenameDialog from './components/RenameDialog.vue'
import Sidebar from './components/Sidebar.vue'
import { useAgentHarness } from './composables/useAgentHarness'

const {
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
  sendMessage
} = useAgentHarness()
</script>
