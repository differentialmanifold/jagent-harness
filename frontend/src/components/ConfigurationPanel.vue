<template>
  <section class="configuration-shell">
    <button
      v-if="mobileNavOpen"
      class="configuration-nav-backdrop"
      type="button"
      aria-label="Close configuration navigation"
      @click="mobileNavOpen = false"
    ></button>

    <aside :class="['configuration-sidebar', { open: mobileNavOpen }]">
      <header class="configuration-sidebar-header">
        <div>
          <h2>Configuration</h2>
          <p>Agent runtime resources</p>
        </div>
        <el-button class="configuration-nav-close" text :icon="Close" circle title="Close" @click="mobileNavOpen = false" />
      </header>

      <div class="configuration-project-picker">
        <label>Project context</label>
        <el-dropdown v-if="projectGroups.length" trigger="click" @command="selectProjectContext">
          <button class="configuration-project-trigger" type="button" :title="selectedProject?.name">
            <el-icon><FolderOpened /></el-icon>
            <span>{{ selectedProject?.name || 'Select project' }}</span>
            <el-icon><CaretBottom /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-for="project in projectGroups"
                :key="project.key"
                :command="project.key"
                :class="{ selected: project.key === selectedProjectKey }"
              >
                {{ project.name }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <div v-else class="configuration-project-empty">No projects</div>
        <small v-if="selectedProject" :title="selectedProject.pathLabel">{{ selectedProject.pathLabel }}</small>
      </div>

      <nav class="configuration-navigation" aria-label="Configuration resources">
        <section class="configuration-nav-group">
          <h3>Runtime</h3>
          <button
            :class="['configuration-nav-item', { active: activeItem === 'runtime-final' }]"
            type="button"
            @click="navigate('runtime-final')"
          >
            <el-icon><View /></el-icon>
            <span>Final Prompt</span>
          </button>
        </section>

        <section class="configuration-nav-group">
          <h3>Global</h3>
          <button
            v-for="item in globalResourceItems"
            :key="`global-${item.key}`"
            :class="['configuration-nav-item', { active: activeItem === `global-${item.key}` }]"
            type="button"
            :aria-label="`Global ${item.label}`"
            @click="navigate(`global-${item.key}`)"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </button>
        </section>

        <section class="configuration-nav-group">
          <h3 :title="selectedProject?.name || 'No project selected'">
            Current project<span v-if="selectedProject"> · {{ selectedProject.name }}</span>
          </h3>
          <button
            v-for="item in projectResourceItems"
            :key="`project-${item.key}`"
            :class="['configuration-nav-item', { active: activeItem === `project-${item.key}` }]"
            type="button"
            :disabled="!selectedProject"
            :aria-label="`Current project ${item.label}`"
            @click="navigate(`project-${item.key}`)"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </button>
        </section>
      </nav>
    </aside>

    <main class="configuration-content">
      <header class="configuration-content-header">
        <el-button
          class="configuration-nav-trigger"
          :icon="Menu"
          circle
          title="Configuration navigation"
          @click="mobileNavOpen = true"
        />
        <div class="configuration-heading-copy">
          <nav class="configuration-breadcrumbs" aria-label="Breadcrumb">
            <span>{{ scopeTitle }}</span>
            <el-icon><ArrowRight /></el-icon>
            <strong>{{ resourceTitle }}</strong>
          </nav>
          <h2>{{ resourceTitle }}</h2>
          <p>{{ resourceSubtitle }}</p>
        </div>
      </header>

      <div class="configuration-resource">
        <FinalPromptPanel
          v-if="activeItem === 'runtime-final'"
          ref="activePanelRef"
          :session-id="selectedSessionId"
        />
        <KnowledgePanel
          v-else-if="activeResource === 'agent-rules'"
          :key="contentKey"
          ref="activePanelRef"
          mode="prompts"
          :scope="activeScope"
          :session-id="activeSessionId"
          @changed="$emit('changed')"
        />
        <KnowledgePanel
          v-else-if="activeResource === 'skills'"
          :key="contentKey"
          ref="activePanelRef"
          mode="skills"
          :scope="activeScope"
          :session-id="activeSessionId"
          @changed="$emit('changed')"
        />
        <BuiltInToolsPanel
          v-else-if="activeResource === 'built-in-tools'"
          ref="activePanelRef"
          :session-id="selectedSessionId"
          @changed="$emit('changed')"
        />
        <McpPanel
          v-else-if="activeResource === 'mcp'"
          :key="contentKey"
          ref="activePanelRef"
          :scope="activeScope"
          :session-id="activeSessionId"
          @changed="$emit('changed')"
        />
      </div>
    </main>
  </section>
</template>

<script setup>
import { computed, markRaw, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { ArrowRight, CaretBottom, Close, Connection, Document, Files, FolderOpened, Menu, SetUp, View } from '@element-plus/icons-vue'
import BuiltInToolsPanel from './BuiltInToolsPanel.vue'
import FinalPromptPanel from './FinalPromptPanel.vue'
import KnowledgePanel from './KnowledgePanel.vue'
import McpPanel from './McpPanel.vue'

const props = defineProps({
  projectGroups: { type: Array, required: true },
  currentProjectKey: { type: String, default: '' },
  mcpAvailable: { type: Boolean, default: false }
})

defineEmits(['changed'])

const activeItem = ref('runtime-final')
const selectedProjectKey = ref('')
const activePanelRef = ref(null)
const mobileNavOpen = ref(false)
const projectSelectionTouched = ref(false)

const globalResourceItems = computed(() => {
  const items = [
    { key: 'agent-rules', label: 'Agent Rules', icon: markRaw(Document) },
    { key: 'skills', label: 'Skills', icon: markRaw(Files) },
    { key: 'built-in-tools', label: 'Built-in Tools', icon: markRaw(SetUp) }
  ]
  if (props.mcpAvailable) {
    items.push({ key: 'mcp', label: 'MCP Servers', icon: markRaw(Connection) })
  }
  return items
})
const projectResourceItems = computed(() => globalResourceItems.value.filter((item) => item.key !== 'built-in-tools'))

const selectedProject = computed(() => (
  props.projectGroups.find((project) => project.key === selectedProjectKey.value) || null
))
const selectedSessionId = computed(() => selectedProject.value?.sessions?.[0]?.sessionId || '')
const activeScope = computed(() => activeItem.value.startsWith('project-') ? 'project' : 'global')
const activeResource = computed(() => {
  if (activeItem.value === 'runtime-final') return 'final-prompt'
  return activeItem.value.replace(/^(global|project)-/, '')
})
const activeSessionId = computed(() => activeScope.value === 'project' ? selectedSessionId.value : '')
const contentKey = computed(() => `${activeItem.value}:${activeSessionId.value}`)
const scopeTitle = computed(() => {
  if (activeItem.value === 'runtime-final') return 'Runtime'
  return activeScope.value === 'project' ? 'Current project' : 'Global'
})
const resourceTitle = computed(() => ({
  'final-prompt': 'Final Prompt',
  'agent-rules': 'Agent Rules',
  skills: 'Skills',
  'built-in-tools': 'Built-in Tools',
  mcp: 'MCP Servers'
})[activeResource.value] || 'Configuration')
const resourceSubtitle = computed(() => {
  const projectName = selectedProject.value?.name || 'the selected project'
  if (activeResource.value === 'final-prompt') {
    return selectedProject.value
      ? `Effective runtime prompt for ${projectName}.`
      : 'Global runtime prompt. Select a project to include project-specific configuration.'
  }
  if (activeScope.value === 'global') {
    return ({
      'agent-rules': 'Shared AGENTS.md rules loaded for every project.',
      skills: 'Shared skills available to every project.',
      'built-in-tools': 'Application-wide built-in tools exposed to every project.',
      mcp: 'Shared MCP servers available to every project.'
    })[activeResource.value]
  }
  return ({
    'agent-rules': `AGENTS.md rules appended for ${projectName}.`,
    skills: `Skills scoped to ${projectName}; same-name skills override global versions.`,
    mcp: `MCP configuration for ${projectName}; same-name servers override global versions.`
  })[activeResource.value]
})

watch(
  () => [props.projectGroups, props.currentProjectKey],
  () => {
    if (selectedProject.value && projectSelectionTouched.value) return
    const current = props.projectGroups.find((project) => project.key === props.currentProjectKey)
    if (!selectedProject.value || current) {
      selectedProjectKey.value = current?.key || props.projectGroups[0]?.key || ''
    }
    if (!selectedProjectKey.value && activeItem.value.startsWith('project-')) {
      activeItem.value = 'runtime-final'
    }
  },
  { immediate: true, deep: true }
)

async function navigate(item) {
  if (item === activeItem.value) {
    mobileNavOpen.value = false
    return
  }
  if (item.startsWith('project-') && !selectedProject.value) return
  if (!await confirmDiscard()) return
  activeItem.value = item
  mobileNavOpen.value = false
}

async function selectProjectContext(projectKey) {
  if (projectKey === selectedProjectKey.value) return
  if (!await confirmDiscard()) return
  projectSelectionTouched.value = true
  selectedProjectKey.value = projectKey
}

async function confirmDiscard() {
  if (!activePanelRef.value?.hasUnsavedChanges?.()) return true
  try {
    await ElMessageBox.confirm(
      'Discard the unsaved changes on this page?',
      'Unsaved changes',
      {
        type: 'warning',
        confirmButtonText: 'Discard changes',
        cancelButtonText: 'Keep editing'
      }
    )
    activePanelRef.value?.discardChanges?.()
    return true
  } catch {
    return false
  }
}

defineExpose({ beforeLeave: confirmDiscard })
</script>
