<template>
  <section :class="['configuration-shell', { 'system-settings': mode === 'global' }]">
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
          <h2>{{ mode === 'global' ? 'System Settings' : 'Configuration' }}</h2>
          <p>{{ mode === 'global' ? 'Defaults by workspace type' : 'Project settings and runtime' }}</p>
        </div>
        <el-button class="configuration-nav-close" text :icon="Close" circle title="Close" @click="mobileNavOpen = false" />
      </header>

      <div v-if="mode !== 'global'" class="configuration-project-picker">
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
        <section v-if="mode === 'project'" class="configuration-nav-group">
          <h3>Project</h3>
          <button :class="['configuration-nav-item', { active: activeItem === 'general' }]" @click="navigate('general')">General</button>
        </section>
        <section v-if="mode !== 'global'" class="configuration-nav-group">
          <h3>Runtime</h3>
          <button
            :class="['configuration-nav-item', { active: activeItem === 'runtime-final' }]"
            type="button"
            @click="navigate('runtime-final')"
          >
            <el-icon><View /></el-icon>
            <span>Runtime Context</span>
          </button>
        </section>

        <section v-for="target in (mode === 'global' ? globalScopes : [])" :key="target.scope" class="configuration-nav-group">
          <h3>{{ target.label }}</h3>
          <button v-for="item in globalResourceItems" :key="item.key"
            :class="['configuration-nav-item', { active: activeItem === `${target.scope}-${item.key}` }]"
            type="button" :aria-label="`${target.label} ${item.label}`" @click="navigate(`${target.scope}-${item.key}`)">
            <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
          </button>
        </section>

        <section v-if="mode !== 'global'" class="configuration-nav-group">
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
        <section v-if="activeItem === 'general'" class="project-general">
          <template v-if="selectedProject">
            <h3>{{ selectedProject.name }}</h3>
            <p><strong>Runner:</strong> {{ selectedProject.sessions?.[0]?.runnerId || 'Server tools only' }}</p>
            <p><strong>Workspace:</strong> <code>{{ selectedProject.workspacePath || 'No local workspace' }}</code></p>
            <p>{{ selectedProject.sessions.length }} chats share this project's environment and configuration.</p>
            <el-button @click="$emit('rename-project', selectedProject)">Rename project</el-button>
          </template>
          <el-empty v-else description="Choose or create a project first." />
        </section>
        <RuntimeContextPanel
          v-else-if="activeItem === 'runtime-final'"
          ref="activePanelRef"
          :session-id="selectedSessionId"
          :title="selectedProject?.sessions?.find(s => s.sessionId === selectedSessionId)?.title"
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
          :server-editable="mode !== 'project'"
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
import RuntimeContextPanel from './RuntimeContextPanel.vue'
import KnowledgePanel from './KnowledgePanel.vue'
import McpPanel from './McpPanel.vue'

const props = defineProps({
  mode: { type: String, default: 'project' },
  sessionId: { type: String, default: '' },
  projectGroups: { type: Array, required: true },
  currentProjectKey: { type: String, default: '' },
  mcpAvailable: { type: Boolean, default: false }
})

defineEmits(['changed', 'rename-project'])

const activeItem = ref(props.mode === 'global' ? 'global-agent-rules' : 'general')
const globalScopes = [{ scope: 'global', label: 'Project defaults' }, { scope: 'agent-global', label: 'Agent defaults' }]
const selectedProjectKey = ref('')
const activePanelRef = ref(null)
const mobileNavOpen = ref(false)
const projectSelectionTouched = ref(false)

const globalResourceItems = computed(() => {
  const items = [
    { key: 'agent-rules', label: 'Instructions', icon: markRaw(Document) },
    { key: 'skills', label: 'Skills', icon: markRaw(Files) }
  ]
  if (props.mcpAvailable) {
    items.push({ key: 'mcp', label: 'MCP Servers', icon: markRaw(Connection) })
  }
  return items
})
const projectResourceItems = computed(() => [...globalResourceItems.value, { key: 'built-in-tools', label: 'Tools', icon: markRaw(SetUp) }])

const selectedProject = computed(() => (
  props.projectGroups.find((project) => project.key === selectedProjectKey.value) || null
))
const selectedSessionId = computed(() => props.mode === 'global' ? '' : (selectedProject.value?.sessions?.some(s => s.sessionId === props.sessionId) ? props.sessionId : selectedProject.value?.sessions?.[0]?.sessionId) || '')
const activeScope = computed(() => activeItem.value.startsWith('agent-global-') ? 'agent-global' : activeItem.value.startsWith('project-') ? 'project' : 'global')
const activeResource = computed(() => {
  if (activeItem.value === 'runtime-final') return 'final-prompt'
  return activeItem.value.replace(/^(agent-global|global|project)-/, '')
})
const activeSessionId = computed(() => activeScope.value === 'project' ? selectedSessionId.value : '')
const contentKey = computed(() => `${activeItem.value}:${activeSessionId.value}`)
const scopeTitle = computed(() => {
  if (activeItem.value === 'runtime-final') return 'Runtime'
  if (activeItem.value === 'general') return 'Current project'
  return activeScope.value === 'project' ? 'Current project' : activeScope.value === 'agent-global' ? 'Agent defaults' : 'Project defaults'
})
const resourceTitle = computed(() => ({
  general: 'General',
  'final-prompt': 'Runtime Context',
  'agent-rules': 'Instructions',
  skills: 'Skills',
  'built-in-tools': 'Tools',
  mcp: 'MCP Servers'
})[activeResource.value] || 'Configuration')
const resourceSubtitle = computed(() => {
  const projectName = selectedProject.value?.name || 'the selected project'
  if (activeItem.value === 'general') return 'Project identity and execution environment.'
  if (activeResource.value === 'final-prompt') return `Model, available tools, Skills and prompt for a chat in ${projectName}.`
  if (activeScope.value !== 'project') {
    const target = activeScope.value === 'agent-global' ? 'Agents' : 'projects';
    return `Shared ${resourceTitle.value.toLowerCase()} for ${target} only. Changes apply to subsequent model calls.`
  }
  return ({
    'agent-rules': `AGENTS.md rules appended for ${projectName}.`,
    'built-in-tools': `Runner tool selection for ${projectName}; shared server tools are read-only here.`,
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
      activeItem.value = 'general'
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

<style scoped>
.system-settings .configuration-sidebar { grid-template-rows: auto minmax(0, 1fr); }
.project-general { height: 100%; overflow: auto; padding: 20px; overflow-wrap: anywhere; }
.project-general h3 { margin-top: 0; }
.project-general p { line-height: 1.6; }
</style>
