<template>
  <aside class="sidebar">
    <div class="session-sidebar-header">
      <div>
        <h2>Chats</h2>
        <p>{{ projectGroups.length }} projects</p>
      </div>
    </div>

    <div class="project-actions">
      <el-button type="primary" :icon="Plus" @click="$emit('open-project-dialog')">
        Add new project
      </el-button>
    </div>

    <el-scrollbar class="projects-scroll">
      <nav class="projects" aria-label="Projects">
        <section
          v-for="project in projectGroups"
          :key="project.key"
          :class="['project-group', { active: project.key === currentProjectKey, collapsed: isCollapsed(project.key) }]"
        >
          <div class="project-header">
            <button class="project-title" :title="projectTitle(project)" @click="toggleProject(project)">
              <el-icon class="project-disclosure" aria-hidden="true">
                <CaretRight v-if="isCollapsed(project.key)" />
                <CaretBottom v-else />
              </el-icon>
              <span class="project-title-text">
                <span :title="project.name">{{ project.name }}</span>
                <small :title="project.pathLabel">{{ project.pathLabel }}</small>
              </span>
            </button>
            <el-dropdown trigger="click">
              <el-button text :icon="MoreFilled" circle title="Project actions" />
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="runProjectAction('create-session', project)">New chat</el-dropdown-item>
                  <el-dropdown-item @click="runProjectAction('rename-project', project)">Rename</el-dropdown-item>
                  <el-dropdown-item :disabled="running" divided @click="runProjectAction('remove-project', project)">
                    Remove
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>

          <div v-if="!isCollapsed(project.key)" class="sessions">
            <div
              v-for="session in project.sessions"
              :key="session.sessionId"
              :class="['session-item', { active: currentSession && currentSession.sessionId === session.sessionId }]"
            >
              <button class="session-main" :title="session.title" @click="selectSession(session.sessionId)">
                <span :title="session.title">{{ session.title }}</span>
              </button>
              <el-dropdown trigger="click">
                <el-button text :icon="MoreFilled" circle title="Chat actions" />
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item :disabled="running" @click="runChatAction('rename-chat', session)">Rename</el-dropdown-item>
                    <el-dropdown-item :disabled="running" divided @click="runChatAction('remove-chat', session)">Remove</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>
        </section>
      </nav>
    </el-scrollbar>
  </aside>
</template>

<script setup>
import { ref, watch } from 'vue'
import { CaretBottom, CaretRight, MoreFilled, Plus } from '@element-plus/icons-vue'

const props = defineProps({
  projectGroups: { type: Array, required: true },
  currentProjectKey: { type: String, required: true },
  currentSession: { type: Object, default: null },
  running: { type: Boolean, required: true }
})

const emit = defineEmits([
  'open-project-dialog',
  'select-project',
  'create-session',
  'rename-project',
  'remove-project',
  'select-session',
  'rename-chat',
  'remove-chat'
])

const collapsedProjectKeys = ref([])

watch(
  () => [props.currentProjectKey, props.projectGroups],
  () => {
    if (!props.currentProjectKey) return
    collapsedProjectKeys.value = collapsedProjectKeys.value.filter((key) => key !== props.currentProjectKey)
  },
  { immediate: true }
)

function toggleProject(project) {
  if (isCollapsed(project.key)) {
    collapsedProjectKeys.value = collapsedProjectKeys.value.filter((key) => key !== project.key)
    emit('select-project', project)
  } else {
    collapsedProjectKeys.value = [...collapsedProjectKeys.value, project.key]
  }
}

function selectSession(sessionId) {
  const project = props.projectGroups.find((item) => item.sessions.some((session) => session.sessionId === sessionId))
  if (project) {
    collapsedProjectKeys.value = collapsedProjectKeys.value.filter((key) => key !== project.key)
  }
  emit('select-session', sessionId)
}

function isCollapsed(projectKey) {
  return collapsedProjectKeys.value.includes(projectKey)
}

function projectTitle(project) {
  return project.pathLabel ? `${project.name}\n${project.pathLabel}` : project.name
}

function runProjectAction(action, project) {
  emit(action, project)
}

function runChatAction(action, session) {
  emit(action, session)
}
</script>
