<template>
  <aside class="sidebar">
    <div class="brand">
      <div>
        <h1>JAgentHarness</h1>
        <p>{{ providerLabel }}</p>
      </div>
      <button class="icon-button" title="Refresh" @click="$emit('refresh')">R</button>
    </div>

    <div class="project-actions">
      <button class="primary-button" @click="$emit('open-project-dialog')">
        Add new project
      </button>
    </div>

    <nav class="projects" aria-label="Projects">
      <section
        v-for="project in projectGroups"
        :key="project.key"
        :class="['project-group', { active: project.key === currentProjectKey, collapsed: isCollapsed(project.key) }]"
      >
        <div class="project-header">
          <button class="project-title" @click="toggleProject(project)">
            <span class="project-disclosure" aria-hidden="true">{{ isCollapsed(project.key) ? '>' : 'v' }}</span>
            <span class="project-title-text">
              <span>{{ project.name }}</span>
              <small>{{ project.pathLabel }}</small>
            </span>
          </button>
          <div class="menu-wrapper">
            <button class="menu-button" title="Project actions" @click="toggleMenu('project', project.key)">
              ...
            </button>
            <div v-if="isMenuOpen('project', project.key)" class="context-menu">
              <button @click="runProjectAction('create-session', project)">New chat</button>
              <button @click="runProjectAction('rename-project', project)">Rename</button>
              <button class="danger" :disabled="running" @click="runProjectAction('remove-project', project)">Remove</button>
            </div>
          </div>
        </div>

        <div v-if="!isCollapsed(project.key)" class="sessions">
          <div
            v-for="session in project.sessions"
            :key="session.sessionId"
            :class="['session-item', { active: currentSession && currentSession.sessionId === session.sessionId }]"
          >
            <button class="session-main" @click="selectSession(session.sessionId)">
              <span>{{ session.title }}</span>
              <small>{{ formatDate(session.updatedAt) }}</small>
            </button>
            <div class="menu-wrapper">
              <button class="menu-button" title="Chat actions" @click="toggleMenu('chat', session.sessionId)">
                ...
              </button>
              <div v-if="isMenuOpen('chat', session.sessionId)" class="context-menu">
                <button :disabled="running" @click="runChatAction('rename-chat', session)">Rename</button>
                <button class="danger" :disabled="running" @click="runChatAction('remove-chat', session)">Remove</button>
              </div>
            </div>
          </div>
        </div>
      </section>
    </nav>
  </aside>
</template>

<script setup>
import { ref, watch } from 'vue'
import { formatDate } from '../utils/format'

const props = defineProps({
  providerLabel: { type: String, required: true },
  projectGroups: { type: Array, required: true },
  currentProjectKey: { type: String, required: true },
  currentSession: { type: Object, default: null },
  running: { type: Boolean, required: true }
})

const emit = defineEmits([
  'refresh',
  'open-project-dialog',
  'select-project',
  'create-session',
  'rename-project',
  'remove-project',
  'select-session',
  'rename-chat',
  'remove-chat'
])

const openMenu = ref(null)
const collapsedProjectKeys = ref([])

watch(
  () => [props.currentProjectKey, props.projectGroups],
  () => {
    if (!props.currentProjectKey) return
    collapsedProjectKeys.value = collapsedProjectKeys.value.filter((key) => key !== props.currentProjectKey)
  },
  { immediate: true }
)

function toggleMenu(type, key) {
  if (openMenu.value && openMenu.value.type === type && openMenu.value.key === key) {
    openMenu.value = null
  } else {
    openMenu.value = { type, key }
  }
}

function isMenuOpen(type, key) {
  return openMenu.value && openMenu.value.type === type && openMenu.value.key === key
}

function closeMenu() {
  openMenu.value = null
}

function toggleProject(project) {
  closeMenu()
  if (isCollapsed(project.key)) {
    collapsedProjectKeys.value = collapsedProjectKeys.value.filter((key) => key !== project.key)
    emit('select-project', project)
  } else {
    collapsedProjectKeys.value = [...collapsedProjectKeys.value, project.key]
  }
}

function selectSession(sessionId) {
  closeMenu()
  const project = props.projectGroups.find((item) => item.sessions.some((session) => session.sessionId === sessionId))
  if (project) {
    collapsedProjectKeys.value = collapsedProjectKeys.value.filter((key) => key !== project.key)
  }
  emit('select-session', sessionId)
}

function isCollapsed(projectKey) {
  return collapsedProjectKeys.value.includes(projectKey)
}

function runProjectAction(action, project) {
  closeMenu()
  emit(action, project)
}

function runChatAction(action, session) {
  closeMenu()
  emit(action, session)
}
</script>
