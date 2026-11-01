<template>
  <section class="agent-tasks" aria-label="Agent tasks">
    <header class="tasks-heading">
      <div>
        <h2>Goals & progress</h2>
        <p>Your Agent maintains this plan. Ask in Chat to change direction.</p>
      </div>
      <span v-if="tasks.length" class="tasks-summary">
        {{ progress.done }}/{{ progress.total }} steps done
        <span v-if="progress.cancelled"> · {{ progress.cancelled }} cancelled</span>
      </span>
    </header>

    <el-empty v-if="!tasks.length" description="Describe a goal in Chat. Your Agent will track the work here when it needs a plan." :image-size="80" />
    <template v-else>
      <div class="tasks-toolbar">
        <input v-model="query" type="search" placeholder="Search goals, plans or progress…" aria-label="Search tasks" />
        <div class="tasks-filters" aria-label="Filter tasks">
          <button v-for="option in filters" :key="option.value" type="button" :aria-pressed="filter === option.value" @click="filter = option.value">{{ option.label }}</button>
        </div>
      </div>
      <div class="tasks-workspace">
        <nav class="task-list" aria-label="Goals and steps">
          <p v-if="!rows.length" class="tasks-empty">No matching tasks. Try another search or filter.</p>
          <div v-for="row in rows" :key="row.task.id" class="task-row" :class="{ selected: selectedId === row.task.id, 'context-only': row.contextOnly }" :style="{ '--depth': Math.min(row.depth, 5) }">
            <button v-if="row.children.length" class="task-expand" type="button" :aria-label="`${row.expanded ? 'Collapse' : 'Expand'} ${row.task.title}`" :aria-expanded="row.expanded" :disabled="filtering" @click="toggle(row.task.id)">
              <span :class="{ expanded: row.expanded }" aria-hidden="true">›</span>
            </button>
            <span v-else class="task-indent" aria-hidden="true"></span>
            <button type="button" class="task-select" :aria-current="selectedId === row.task.id ? 'true' : undefined" @click="selectTask(row.task.id)">
              <span class="task-title-line"><span class="task-dot" :data-status="row.task.status" aria-hidden="true"></span><strong>{{ row.task.title }}</strong></span>
              <span class="task-row-meta">{{ statusLabel(row.task) }}<span v-if="row.children.length"> · {{ childProgress(row).done }}/{{ childProgress(row).total }} steps done</span></span>
              <span v-if="row.task.progress" class="task-row-progress">{{ row.task.progress }}</span>
            </button>
          </div>
        </nav>

        <article v-if="selected" :key="selected.id" class="task-detail" aria-label="Task details">
          <button v-if="parent" type="button" class="task-link task-parent" @click="revealTask(parent.id)">↑ {{ parent.title }}</button>
          <header class="task-detail-heading">
            <span class="task-badge" :data-status="selected.status">{{ statusLabel(selected) }}</span>
            <h3>{{ selected.title }}</h3>
            <div class="task-links">
              <button v-if="selected.conversationId && hasConversation(selected.conversationId)" type="button" class="task-link" @click="$emit('open-chat', selected.conversationId)">Open Agent chat ↗</button>
              <button v-if="selected.sessionId" type="button" class="task-link" @click="$emit('open-project', selected.sessionId)">Open project ↗</button>
            </div>
          </header>

          <div v-if="selectedNode?.children.length" class="task-completion">
            <div class="task-meter" role="progressbar" :aria-valuenow="selectedProgress.done" :aria-valuemin="0" :aria-valuemax="selectedProgress.total" :aria-label="`${selectedProgress.done} of ${selectedProgress.total} steps done`">
              <span :style="{ width: `${100 * selectedProgress.done / selectedProgress.total}%` }"></span>
            </div>
            <small>{{ selectedProgress.done }} of {{ selectedProgress.total }} steps done<span v-if="selectedProgress.cancelled"> · {{ selectedProgress.cancelled }} cancelled</span></small>
          </div>

          <div class="task-detail-tabs" aria-label="Task details view">
            <button v-for="section in detailSections" :key="section.value" type="button" :aria-pressed="detailTab === section.value" @click="detailTab = section.value">{{ section.label }}<span v-if="section.value === 'evidence'"> ({{ selected.evidence?.length || 0 }})</span></button>
          </div>

          <div v-if="detailTab === 'overview'" class="task-sections">
            <section class="task-current">
              <h4>{{ selected.status === 'WAITING' ? 'Waiting / next action' : isTaskFinished(selected) ? 'Outcome' : 'Progress / next action' }}</h4>
              <MarkdownContent v-if="selected.progress" :content="selected.progress" />
              <p v-else class="task-muted">No progress recorded yet.</p>
              <p v-if="selected.nextCheckAt" class="task-next-check">Next check · {{ formatTime(selected.nextCheckAt) }}</p>
            </section>
            <section v-if="blockedBy.length" class="task-blocked">
              <h4>Waiting on {{ blockedBy.length }} {{ blockedBy.length === 1 ? 'dependency' : 'dependencies' }}</h4>
              <p>The Agent can proceed when these tasks are done.</p>
              <div v-for="dependency in blockedBy" :key="dependency.id">
                <button v-if="dependency.task" type="button" class="task-link" @click="revealTask(dependency.id)">{{ dependency.task.title }} · {{ statusLabel(dependency.task) }}</button>
                <span v-else>Task no longer available · {{ dependency.id }}</span>
              </div>
            </section>
            <section>
              <h4>Plan & scope</h4>
              <MarkdownContent v-if="selected.description" :content="selected.description" />
              <p v-else class="task-muted">No detailed plan recorded.</p>
            </section>
            <section>
              <h4>Acceptance criteria</h4>
              <MarkdownContent v-if="selected.acceptance" :content="selected.acceptance" />
              <p v-else class="task-muted">No acceptance criteria recorded.</p>
            </section>
            <section v-if="dependencies.length && !blockedBy.length">
              <h4>Dependencies completed</h4>
              <button v-for="dependency in dependencies" :key="dependency.id" type="button" class="task-link task-dependency" @click="revealTask(dependency.id)">✓ {{ dependency.task.title }}</button>
            </section>
          </div>

          <div v-else-if="detailTab === 'evidence'" class="task-sections">
            <p class="task-muted">Project outputs the Agent used to check the acceptance criteria.</p>
            <p v-if="!selected.evidence?.length" class="tasks-empty">No verification evidence recorded yet.</p>
            <section v-for="(evidence, index) in selected.evidence" :key="`${evidence.messageId}:${index}`" class="task-evidence">
              <h4>Evidence {{ index + 1 }}</h4>
              <MarkdownContent :content="evidence.note || 'No verification note recorded.'" />
              <button v-if="evidence.sessionId" type="button" class="task-link" @click="$emit('open-project', evidence.sessionId)">{{ projectTitle(evidence.sessionId) }} ↗</button>
              <details><summary>Reference</summary><code>Message: {{ evidence.messageId }}</code></details>
            </section>
          </div>

          <div v-else class="task-sections" :aria-busy="runsLoading">
            <p class="task-muted">Each attempt keeps its requirements and project report. A returned result still needs the Agent's review.</p>
            <p v-if="runsError" role="alert" class="task-error">{{ runsError }} <button type="button" class="task-link" @click="retry++">Retry</button></p>
            <p v-else-if="runsLoading && !runs.length" class="tasks-empty">Loading execution history…</p>
            <p v-else-if="!runs.length" class="tasks-empty">No tracked executions yet.</p>
            <section v-for="run in runs" :key="run.id" class="task-run">
              <div class="task-run-heading"><strong>{{ run.status === 'WAITING' ? 'Awaiting project result' : run.error ? 'Needs attention' : 'Project result received' }}</strong><time>{{ formatTime(run.createdAt) }}</time></div>
              <p class="task-muted">{{ run.readOnly ? 'Read-only investigation' : 'May change project files' }}</p>
              <button v-if="run.projectSessionId" type="button" class="task-link" @click="$emit('open-project', run.projectSessionId)">{{ projectTitle(run.projectSessionId) }} ↗</button>
              <p v-if="run.error" class="task-error">{{ run.error }}</p>
              <details><summary>Requirements at dispatch</summary><pre>{{ formatRequirements(run.taskRequirements) }}</pre></details>
              <details><summary>Request / result</summary><MarkdownContent :content="run.text || 'No report available.'" /></details>
              <details><summary>Run reference</summary><code>{{ run.projectRunId }}</code></details>
            </section>
          </div>
        </article>
        <p v-else class="tasks-empty">Select a goal or step to inspect its progress.</p>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { request } from '../api/http'
import MarkdownContent from './MarkdownContent.vue'
import { buildTaskTree, visibleTaskRows, taskProgress, taskDependencies, taskStatusLabels, isTaskFinished } from '../utils/agentTasks'

const props = defineProps({
  agentId: { type: String, required: true },
  tasks: { type: Array, default: () => [] },
  projects: { type: Array, default: () => [] },
  conversations: { type: Array, default: () => [] },
  active: Boolean,
})
defineEmits(['open-project', 'open-chat'])
const filters = [{ value: 'all', label: 'All' }, { value: 'active', label: 'Active' }, { value: 'done', label: 'Done' }, { value: 'cancelled', label: 'Cancelled' }]
const detailSections = [{ value: 'overview', label: 'Overview' }, { value: 'evidence', label: 'Evidence' }, { value: 'runs', label: 'Executions' }]
const query = ref(''), filter = ref('all'), collapsed = ref(new Set()), selectedId = ref(''), detailTab = ref('overview')
const roots = computed(() => buildTaskTree(props.tasks))
const progress = computed(() => taskProgress(roots.value))
const rows = computed(() => visibleTaskRows(roots.value, { query: query.value, filter: filter.value, collapsed: collapsed.value }))
const filtering = computed(() => Boolean(query.value.trim()) || filter.value !== 'all')
const selected = computed(() => props.tasks.find(task => task.id === selectedId.value))
const selectedNode = computed(() => visibleTaskRows(roots.value).find(row => row.task.id === selectedId.value))
const selectedProgress = computed(() => taskProgress(selectedNode.value ? [selectedNode.value] : []))
const parent = computed(() => props.tasks.find(task => task.id === selected.value?.parentId))
const dependencies = computed(() => taskDependencies(selected.value || {}, props.tasks))
const blockedBy = computed(() => dependencies.value.filter(dependency => !dependency.satisfied))
const childProgress = row => taskProgress(row.children)
const statusLabel = task => taskStatusLabels[task.status] || task.status
const formatTime = value => new Date(value).toLocaleString()
const hasConversation = id => props.conversations.some(chat => chat.sessionId === id)
const projectTitle = id => {
  const session = props.projects.find(project => project.sessionId === id)
  return session ? `${session.projectName || 'Project'} / ${session.title}` : 'Open project conversation'
}
function formatRequirements(value) {
  try {
    const plan = JSON.parse(value)
    return [plan.title, plan.description, plan.acceptance && `Acceptance criteria:\n${plan.acceptance}`,
      plan.dependsOn?.length && `Dependencies: ${plan.dependsOn.join(', ')}`].filter(Boolean).join('\n\n') || value
  } catch { return value || 'No tracked requirements' }
}
function selectTask(id) { selectedId.value = id; detailTab.value = 'overview' }
function revealTask(id) {
  query.value = ''; filter.value = 'all'; collapsed.value = new Set()
  selectTask(id)
}
function toggle(id) {
  const next = new Set(collapsed.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  collapsed.value = next
}
watch(() => props.agentId, () => {
  selectedId.value = ''; query.value = ''; filter.value = 'all'; collapsed.value = new Set()
}, { flush: 'sync' })
watch(rows, available => {
  if (!available.some(row => row.task.id === selectedId.value)) {
    selectTask(available.find(row => !row.contextOnly && !isTaskFinished(row.task))?.task.id || available[0]?.task.id || '')
  }
}, { immediate: true })

const runs = ref([]), runsError = ref(''), runsLoading = ref(false), retry = ref(0)
watch(selectedId, () => { runs.value = []; runsError.value = '' }, { flush: 'sync' })
// Parent polling supplies fresh tasks. Fetch only the visible execution pane;
// discard late responses on selection changes, navigation and unmount.
watch(() => [props.active, props.agentId, props.tasks, selectedId.value, detailTab.value, retry.value], async (_, __, onCleanup) => {
  if (!props.active || !selectedId.value || detailTab.value !== 'runs') { runsLoading.value = false; return }
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  runsLoading.value = true
  try {
    const result = await request(`/api/v1/personal/agents/${encodeURIComponent(props.agentId)}/cards/${encodeURIComponent(selectedId.value)}/executions`, { signal: controller.signal })
    if (!controller.signal.aborted) {
      runs.value = [...(result || [])].sort((a, b) => b.createdAt - a.createdAt)
      runsError.value = ''
    }
  } catch (error) {
    if (!controller.signal.aborted) runsError.value = error.message
  } finally {
    if (!controller.signal.aborted) runsLoading.value = false
  }
}, { immediate: true })
</script>

<style scoped>
.agent-tasks { container: agent-tasks / inline-size; display: flex; flex-direction: column; gap: 18px; height: 100%; min-height: 0; color: var(--el-text-color-primary); }
.tasks-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.tasks-heading h2 { margin: 0 0 6px; font-size: 19px; }
.tasks-heading p, .task-muted { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; margin: 0; }
.tasks-summary { font-size: 12px; color: var(--el-text-color-regular); font-variant-numeric: tabular-nums; }
.tasks-toolbar { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.tasks-toolbar input { flex: 1 1 220px; min-width: 0; padding: 9px 12px; border: 1px solid var(--el-border-color); border-radius: 8px; color: inherit; background: var(--el-bg-color); font: inherit; font-size: 13px; }
.tasks-filters { display: flex; gap: 3px; padding: 3px; border-radius: 8px; background: var(--el-fill-color-light); }
button { font: inherit; cursor: pointer; color: inherit; }
button:focus-visible, input:focus-visible, summary:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.tasks-filters button { border: 0; border-radius: 6px; background: transparent; padding: 6px 10px; font-size: 12px; }
.tasks-filters button[aria-pressed="true"] { background: var(--el-bg-color); box-shadow: 0 1px 3px #0001; color: var(--el-color-primary); }
.tasks-workspace { display: grid; grid-template-columns: minmax(250px, 35%) minmax(0, 1fr); flex: 1; min-height: 0; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; overflow: hidden; }
.task-list { overflow: auto; padding: 8px; border-right: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-extra-light); }
.task-row { display: flex; align-items: flex-start; padding-left: calc(var(--depth) * 14px); border-radius: 7px; margin-bottom: 3px; }
.task-row:hover { background: var(--el-fill-color-light); }
.task-row.selected { background: var(--el-color-primary-light-9); }
.task-row.context-only .task-title-line { color: var(--el-text-color-secondary); }
.task-expand, .task-indent { flex: 0 0 22px; width: 22px; }
.task-expand { border: 0; background: transparent; margin-top: 9px; padding: 0; height: 24px; font-size: 21px; }
.task-expand span { display: inline-block; transition: transform .15s; }
.task-expand span.expanded { transform: rotate(90deg); }
.task-expand:disabled { cursor: default; }
.task-select { display: flex; flex: 1; min-width: 0; flex-direction: column; gap: 6px; text-align: left; border: 0; border-radius: 6px; padding: 13px 8px 13px 0; background: transparent; }
.task-title-line { display: flex; align-items: flex-start; gap: 8px; font-size: 13px; line-height: 1.5; }
.task-title-line strong { font-weight: 550; overflow-wrap: anywhere; }
.task-dot { flex: 0 0 9px; height: 9px; border: 1.5px solid var(--el-text-color-placeholder); border-radius: 50%; margin-top: 4px; }
.task-dot[data-status="DOING"] { border-color: var(--el-color-primary); background: var(--el-color-primary-light-5); }
.task-dot[data-status="WAITING"] { border-color: var(--el-color-warning); background: var(--el-color-warning-light-5); }
.task-dot[data-status="DONE"] { border-color: var(--el-color-success); background: var(--el-color-success); }
.task-dot[data-status="CANCELLED"] { border-style: dashed; }
.task-row-meta, .task-row-progress { padding-left: 19px; font-size: 11px; color: var(--el-text-color-secondary); line-height: 1.5; }
.task-row-progress { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; }
.task-detail { overflow: auto; min-width: 0; padding: 24px; font-size: 13px; line-height: 1.6; }
.task-detail h3 { font-size: 20px; line-height: 1.45; margin: 10px 0; overflow-wrap: anywhere; }
.task-badge { display: inline-flex; padding: 2px 8px; border-radius: 5px; background: var(--el-fill-color); font-size: 11px; }
.task-badge[data-status="DOING"] { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.task-badge[data-status="WAITING"] { background: var(--el-color-warning-light-9); color: var(--el-color-warning-dark-2); }
.task-badge[data-status="DONE"] { background: var(--el-color-success-light-9); color: var(--el-color-success-dark-2); }
.task-links { display: flex; flex-wrap: wrap; gap: 8px 18px; }
.task-link { border: 0; background: transparent; padding: 2px 0; color: var(--el-color-primary); text-align: left; overflow-wrap: anywhere; font-size: 12px; }
.task-link:hover { text-decoration: underline; }
.task-parent { margin-bottom: 14px; }
.task-completion { margin-top: 18px; color: var(--el-text-color-secondary); }
.task-meter { height: 5px; background: var(--el-fill-color); border-radius: 3px; margin-bottom: 6px; overflow: hidden; }
.task-meter span { display: block; height: 100%; background: var(--el-color-success); }
.task-detail-tabs { display: flex; gap: 20px; border-bottom: 1px solid var(--el-border-color-lighter); margin: 18px 0 22px; }
.task-detail-tabs button { border: 0; border-bottom: 2px solid transparent; background: transparent; padding: 10px 0; font-size: 13px; white-space: nowrap; }
.task-detail-tabs button[aria-pressed="true"] { color: var(--el-color-primary); border-bottom-color: var(--el-color-primary); }
.task-sections { display: grid; gap: 24px; }
.task-sections h4 { font-size: 12px; margin: 0 0 10px; color: var(--el-text-color-regular); }
.task-current { background: var(--el-fill-color-extra-light); border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 16px; }
.task-next-check { color: var(--el-text-color-secondary); margin: 14px 0 0; font-size: 12px; }
.task-blocked { border-left: 3px solid var(--el-color-warning); padding-left: 14px; }
.task-blocked p { margin: 0 0 8px; }
.task-dependency { display: block; }
.task-evidence, .task-run { border-bottom: 1px solid var(--el-border-color-lighter); padding-bottom: 18px; }
.task-evidence > .task-link { margin-top: 12px; }
.task-run-heading { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 6px 14px; }
.task-run-heading time { font-size: 11px; color: var(--el-text-color-secondary); }
.task-run .task-muted { margin: 6px 0; }
details { margin-top: 12px; min-width: 0; }
summary { cursor: pointer; color: var(--el-text-color-secondary); font-size: 12px; padding: 4px 0; }
pre, code { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; }
pre, .task-detail :deep(.chat-markdown pre) { padding: 12px; background: var(--el-fill-color-light); border-radius: 6px; max-width: 100%; overflow: auto; }
.task-detail :deep(.chat-markdown img) { max-width: 100%; }
.task-error { color: var(--el-color-danger); overflow-wrap: anywhere; }
.tasks-empty { padding: 24px 12px; margin: 0; font-size: 13px; line-height: 1.7; color: var(--el-text-color-secondary); }
@container agent-tasks (max-width: 680px) {
  .tasks-workspace { display: flex; flex-direction: column; overflow: auto; }
  .task-list { flex: 0 0 auto; max-height: 260px; border-right: 0; border-bottom: 1px solid var(--el-border-color-lighter); }
  .task-detail { flex: 0 0 auto; overflow: visible; padding: 18px; }
  .task-detail-tabs { gap: 14px; }
}
</style>
