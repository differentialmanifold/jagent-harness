<template>
  <section class="built-in-tools-panel">
    <div class="built-in-tools-toolbar">
      <div>
        <div class="built-in-tools-title">
          <strong>tools.json</strong>
          <el-tag size="small" :type="configured ? 'primary' : 'info'">
            {{ configured ? 'custom selection' : 'all enabled by default' }}
          </el-tag>
        </div>
        <p>{{ selection.length }} of {{ tools.length }} server tools enabled</p>
      </div>
      <div class="built-in-tools-actions">
        <el-button text :disabled="loading || !tools.length" @click="selectAll">Select all</el-button>
        <el-button text :disabled="loading || !tools.length" @click="clearAll">Clear all</el-button>
        <el-popconfirm
          title="Delete tools.json and enable every server tool?"
          confirm-button-text="Reset"
          :disabled="!configured || saving"
          @confirm="resetConfiguration"
        >
          <template #reference>
            <el-button :icon="RefreshLeft" :disabled="!configured" :loading="saving">Reset</el-button>
          </template>
        </el-popconfirm>
        <el-button type="primary" :disabled="!dirty" :loading="saving" @click="save">
          Save changes
        </el-button>
      </div>
    </div>

    <div v-if="error" class="mcp-error built-in-tools-error">{{ error }}</div>

    <div v-if="loading" class="built-in-tools-loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>Loading built-in tools...</span>
    </div>

    <h3 v-if="!loading" class="tool-location-heading">Server tools</h3>
    <el-checkbox-group v-if="!loading" v-model="selection" class="built-in-tool-list">
      <div v-for="tool in tools" :key="tool.name" class="built-in-tool-row">
        <div class="built-in-tool-main">
          <el-checkbox :value="tool.name">
            <code>{{ tool.name }}</code>
          </el-checkbox>
          <el-button size="small" :icon="VideoPlay" @click="openDebugger(tool)">Test</el-button>
        </div>
        <p>{{ tool.description || 'No description provided.' }}</p>
      </div>
      <div v-if="!tools.length" class="built-in-tools-empty">No server tools are registered.</div>
    </el-checkbox-group>

    <p v-if="clientError" class="mcp-error" role="alert">{{ clientError }}</p>
    <section v-if="!loading && clientTools.length" class="client-tools-section">
      <h3 class="tool-location-heading">Client tools <el-tag size="small" type="success">{{ clientTools.length }}</el-tag></h3>
      <p class="client-tools-description">{{ clientSelection.length }} of {{ clientTools.length }} tools enabled for this project. Full access · changes apply to the next run.</p>
      <div class="built-in-tools-actions">
        <el-button text :disabled="clientSaving" @click="clientSelection = clientTools.map(tool => tool.name)">Select all</el-button>
        <el-button text :disabled="clientSaving" @click="clientSelection = []">Clear all</el-button>
        <el-button type="primary" :disabled="!clientDirty" :loading="clientSaving" @click="saveClientTools">Save project tools</el-button>
      </div>
      <el-checkbox-group v-model="clientSelection" class="built-in-tool-list" :disabled="clientSaving">
        <div v-for="tool in clientTools" :key="tool.name" class="built-in-tool-row">
          <div class="built-in-tool-main">
            <el-checkbox :value="tool.name"><code>{{ tool.name }}</code></el-checkbox>
            <el-button size="small" @click="openDebugger(tool, true)">View schema</el-button>
          </div>
          <p v-if="tool.diagnostic" class="tool-diagnostic">
            <el-tag size="small" type="info">Startup backend</el-tag> {{ tool.diagnostic }}
          </p>
          <p>{{ tool.description || 'No description provided.' }}</p>
        </div>
      </el-checkbox-group>
    </section>

    <el-dialog
      v-model="debugOpen"
      class="mcp-debug-dialog"
      :title="debugTool ? `${inspectOnly ? 'Client tool' : 'Test'} ${debugTool.name}` : 'Test server tool'"
      top="20px"
      width="min(760px, calc(100vw - 24px))"
    >
      <div v-if="debugTool" class="mcp-debugger">
        <p>{{ debugTool.description || 'No description provided.' }}</p>
        <div class="mcp-debug-field">
          <strong>Input schema</strong>
          <pre>{{ formatJson(debugTool.parametersSchema || {}) }}</pre>
        </div>
        <div v-if="!inspectOnly" class="mcp-debug-field">
          <strong>Arguments</strong>
          <el-input
            v-model="debugArguments"
            type="textarea"
            :rows="8"
            spellcheck="false"
            placeholder="{}"
          />
        </div>
        <div v-if="debugError" class="mcp-error">{{ debugError }}</div>
        <div v-if="debugResult" class="mcp-debug-field">
          <strong>Result</strong>
          <pre>{{ debugResult }}</pre>
        </div>
      </div>
      <template #footer>
        <div class="mcp-dialog-footer">
          <el-button @click="debugOpen = false">Close</el-button>
          <el-button v-if="!inspectOnly" type="primary" :icon="VideoPlay" :loading="debugging" @click="runDebug">
            Run tool
          </el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { Loading, RefreshLeft, VideoPlay } from '@element-plus/icons-vue'
import { ElCheckbox, ElCheckboxGroup, ElMessage } from 'element-plus'
import { request } from '../api/http'

const props = defineProps({
  sessionId: { type: String, default: '' }
})

const emit = defineEmits(['changed'])
const tools = ref([])
const clientTools = ref([])
const clientSelection = ref([])
const originalClientSelection = ref([])
const clientProjectId = ref('')
const clientSaving = ref(false)
const clientError = ref('')
const clientDirty = computed(() => !sameSelection(clientSelection.value, originalClientSelection.value))
let catalogVersion = 0
watch(() => props.sessionId, async (sessionId) => {
  const version = ++catalogVersion
  clientTools.value = []
  clientProjectId.value = ''
  clientSelection.value = []
  originalClientSelection.value = []
  clientError.value = ''
  if (!sessionId) return
  try {
    const detail = await request('/api/v1/sessions/detail', { method: 'POST', body: JSON.stringify({ sessionId }) })
    if (!detail.session?.runnerId || version !== catalogVersion) return
    const [runners, project] = await Promise.all([
      request('/api/v1/runners'),
      request(`/api/v1/runners/projects/${encodeURIComponent(detail.session.projectId)}`)
    ])
    if (version !== catalogVersion) return
    const runner = runners.find(item => item.runnerId === detail.session.runnerId)
    clientTools.value = (runner?.tools || []).map(tool => ({ ...tool, parametersSchema: tool.parameters, diagnostic: runner?.toolDiagnostics?.[tool.name] }))
    clientProjectId.value = project.projectId
    clientSelection.value = project.enabledTools.filter(name => clientTools.value.some(tool => tool.name === name))
    originalClientSelection.value = [...clientSelection.value]
  } catch (reason) { if (version === catalogVersion) clientError.value = reason.message }
}, { immediate: true })

async function saveClientTools() {
  if (!clientDirty.value || clientSaving.value) return
  const version = catalogVersion
  clientSaving.value = true
  clientError.value = ''
  try {
    const project = await request(`/api/v1/runners/projects/${encodeURIComponent(clientProjectId.value)}/tools`, {
      method: 'PUT', body: JSON.stringify({ enabledTools: clientSelection.value })
    })
    if (version !== catalogVersion) return
    clientSelection.value = [...project.enabledTools]
    originalClientSelection.value = [...project.enabledTools]
    ElMessage.success('Project tools saved. Changes apply to the next run.')
    emit('changed')
  } catch (reason) { if (version === catalogVersion) clientError.value = reason.message }
  finally { clientSaving.value = false }
}
const inspectOnly = ref(false)
const configured = ref(false)
const selection = ref([])
const originalSelection = ref([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const debugOpen = ref(false)
const debugTool = ref(null)
const debugArguments = ref('{}')
const debugResult = ref('')
const debugError = ref('')
const debugging = ref(false)

const dirty = computed(() => !sameSelection(selection.value, originalSelection.value))

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    applyResponse(await request('/api/v1/tools/config'))
  } catch (reason) {
    error.value = reason.message
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!dirty.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    applyResponse(await request('/api/v1/tools/config', {
      method: 'PUT',
      body: JSON.stringify({ enabledTools: selection.value })
    }))
    ElMessage.success('Server tool selection saved.')
    emit('changed')
  } catch (reason) {
    error.value = reason.message
  } finally {
    saving.value = false
  }
}

async function resetConfiguration() {
  if (!configured.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    applyResponse(await request('/api/v1/tools/config', { method: 'DELETE' }))
    ElMessage.success('All server tools are enabled by default.')
    emit('changed')
  } catch (reason) {
    error.value = reason.message
  } finally {
    saving.value = false
  }
}

function applyResponse(data) {
  tools.value = data?.tools || []
  configured.value = Boolean(data?.configured)
  const enabled = normalizeSelection(data?.enabledTools || [])
  selection.value = enabled
  originalSelection.value = [...enabled]
}

function selectAll() {
  selection.value = tools.value.map((tool) => tool.name)
}

function clearAll() {
  selection.value = []
}

function openDebugger(tool, readOnly = false) {
  inspectOnly.value = readOnly
  debugTool.value = tool
  debugArguments.value = '{}'
  debugResult.value = ''
  debugError.value = ''
  debugOpen.value = true
}

async function runDebug() {
  if (!debugTool.value || inspectOnly.value || debugging.value) return
  debugError.value = ''
  debugResult.value = ''
  let args
  try {
    args = JSON.parse(debugArguments.value || '{}')
  } catch (reason) {
    debugError.value = `Invalid arguments JSON: ${reason.message}`
    return
  }
  if (!isPlainObject(args)) {
    debugError.value = 'Arguments must be a JSON object.'
    return
  }
  debugging.value = true
  try {
    const response = await request('/api/v1/tools/call', {
      method: 'POST',
      body: JSON.stringify({
        sessionId: props.sessionId || null,
        toolName: debugTool.value.name,
        arguments: args
      })
    })
    debugResult.value = formatResult(response?.result)
  } catch (reason) {
    debugError.value = reason.message
  } finally {
    debugging.value = false
  }
}

function normalizeSelection(values) {
  return Array.from(new Set(values || [])).sort()
}

function sameSelection(left, right) {
  const normalizedLeft = normalizeSelection(left)
  const normalizedRight = normalizeSelection(right)
  return normalizedLeft.length === normalizedRight.length
    && normalizedLeft.every((value, index) => value === normalizedRight[index])
}

function formatJson(value) {
  return JSON.stringify(value, null, 2)
}

function formatResult(value) {
  if (value == null) return ''
  if (typeof value !== 'string') return formatJson(value)
  try {
    return formatJson(JSON.parse(value))
  } catch {
    return value
  }
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function hasUnsavedChanges() {
  return dirty.value || clientDirty.value
}

function discardChanges() {
  selection.value = [...originalSelection.value]
  clientSelection.value = [...originalClientSelection.value]
}

defineExpose({ hasUnsavedChanges, discardChanges })
</script>

<style scoped>
.tool-diagnostic { overflow-wrap: anywhere; }
</style>
