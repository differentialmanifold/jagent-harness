<template>
  <aside class="sidebar">
    <div class="session-sidebar-header">
      <div>
        <h2>Projects</h2>
        <p>{{ projectGroups.length }} projects</p>
      </div>
    </div>

    <div class="project-owner-switcher">
      <el-dropdown trigger="click" @command="$emit('select-owner', $event)">
        <button class="project-owner-trigger" type="button" aria-label="Project owner">
          <span class="owner-avatar" :class="{ agent: ownerId }"><el-icon><Cpu v-if="ownerId" /><User v-else /></el-icon></span>
          <span class="owner-copy"><strong>{{ ownerName }}</strong><small>{{ ownerId ? 'Agent projects' : 'My projects' }}</small></span>
          <el-icon><CaretBottom /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu class="project-owner-menu">
            <el-dropdown-item command="" :class="{ 'is-selected': !ownerId }"><el-icon><User /></el-icon>My projects<el-icon v-if="!ownerId"><Check /></el-icon></el-dropdown-item>
            <el-dropdown-item v-for="agent in agents" :key="agent.id" :command="agent.id" :class="{ 'is-selected': agent.id === ownerId }" :divided="agent === agents[0]">
              <el-icon><Cpu /></el-icon><span>{{ agent.name }}</span><el-icon v-if="agent.id === ownerId"><Check /></el-icon>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
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
              <el-icon class="project-icon" aria-hidden="true">
                <Folder v-if="isCollapsed(project.key)" />
                <FolderOpened v-else />
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
                  <el-dropdown-item :disabled="!project.workspacePath" @click="copyProjectPath(project)">Copy path</el-dropdown-item>
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
              <ChatActions :busy="running" @rename="runChatAction('rename-chat', session)" @remove="runChatAction('remove-chat', session)" />
            </div>
          </div>
        </section>
      </nav>
    </el-scrollbar>
  </aside>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import ChatActions from './ChatActions.vue'
import { Folder, FolderOpened, MoreFilled, Plus, User, Cpu, CaretBottom, Check } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { writeClipboard } from '../utils/clipboard'

const props = defineProps({
  ownerId: { type: String, default: '' },
  agents: { type: Array, default: () => [] },
  projectGroups: { type: Array, required: true },
  currentProjectKey: { type: String, required: true },
  currentSession: { type: Object, default: null },
  running: { type: Boolean, required: true }
})

const emit = defineEmits([
  'select-owner',
  'open-project-dialog',
  'select-project',
  'create-session',
  'rename-project',
  'remove-project',
  'select-session',
  'rename-chat',
  'remove-chat'
])

const ownerName = computed(() => props.ownerId ? props.agents.find(a => a.id === props.ownerId)?.name || 'Agent' : 'Personal')
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

async function copyProjectPath(project) {
  if (!project.workspacePath) return
  try {
    await writeClipboard(project.workspacePath)
    ElMessage.success('Copied project path.')
  } catch {
    ElMessage.error('Could not copy project path.')
  }
}

function runChatAction(action, session) {
  emit(action, session)
}
</script>

<style scoped>
.project-owner-switcher { flex-shrink: 0; }
.project-owner-switcher :deep(.el-dropdown) { display: block; }
.project-owner-trigger { width: 100%; display: flex; align-items: center; gap: 10px; padding: 10px; border: 1px solid var(--el-border-color-light); border-radius: 10px; background: var(--el-bg-color); color: var(--el-text-color-primary); text-align: left; cursor: pointer; }
.project-owner-trigger:hover { border-color: var(--el-color-primary-light-5); background: var(--el-fill-color-light); }
.project-owner-trigger:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.owner-avatar { display: grid; place-items: center; width: 32px; height: 32px; flex: 0 0 32px; border-radius: 8px; background: #eaf2ff; color: #3c66a9; }
.owner-avatar.agent { background: #f0ecff; color: #7455a8; }
.owner-copy { display: grid; gap: 3px; flex: 1; min-width: 0; }
.owner-copy strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.owner-copy small { color: var(--el-text-color-secondary); font-size: 11px; }
</style>
