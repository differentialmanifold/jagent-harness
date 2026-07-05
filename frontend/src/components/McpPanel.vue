<template>
  <section class="mcp-panel">
    <div class="mcp-config-toolbar">
      <div>
        <strong>mcp.json</strong>
        <span>{{ servers.length }} servers, {{ availableCount }} available</span>
      </div>
      <div class="mcp-detail-actions">
        <el-button
          :type="hasDatabaseConfig ? 'default' : 'primary'"
          :icon="hasDatabaseConfig ? EditPen : DocumentAdd"
          @click="openJsonEditor"
        >
          {{ hasDatabaseConfig ? 'Edit mcp.json' : 'Create mcp.json' }}
        </el-button>
        <el-popconfirm
          v-if="hasDatabaseConfig"
          width="280"
          :title="`Delete the ${scopeLabel} mcp.json?`"
          confirm-button-text="Delete"
          cancel-button-text="Cancel"
          @confirm="deleteDatabaseConfig"
        >
          <template #reference>
            <el-button type="danger" plain :icon="Delete" :loading="saving">Delete mcp.json</el-button>
          </template>
        </el-popconfirm>
      </div>
    </div>

    <div v-if="error" class="mcp-error">{{ error }}</div>

    <div class="mcp-layout">
      <aside class="mcp-server-list">
        <div class="mcp-list-heading">
          <strong>Servers</strong>
          <el-button text :icon="Refresh" :loading="loading" title="Reload configuration" @click="load" />
        </div>
        <button
          v-for="server in servers"
          :key="server.name"
          :class="['mcp-server-row', { active: selectedName === server.name }]"
          :title="server.name"
          @click="selectedName = server.name"
        >
          <span class="mcp-status-dot" :class="server.status" aria-hidden="true"></span>
          <span class="mcp-server-row-text">
            <strong>{{ server.name }}</strong>
            <small>{{ server.source }} · {{ statusLabel(server.status) }}</small>
          </span>
          <span class="mcp-tool-count">{{ server.tools.length }}</span>
        </button>
        <div v-if="!loading && servers.length === 0" class="mcp-empty">No MCP servers configured.</div>
      </aside>

      <main class="mcp-detail">
        <template v-if="selectedServer">
          <header class="mcp-detail-header">
            <div>
              <div class="mcp-title-line">
                <h3>{{ selectedServer.name }}</h3>
                <el-tag size="small" :type="statusType(selectedServer.status)">
                  {{ statusLabel(selectedServer.status) }}
                </el-tag>
                <el-tag size="small">{{ selectedServer.source }}</el-tag>
              </div>
              <p>{{ selectedServer.config.url }}</p>
            </div>
            <div class="mcp-detail-actions">
              <label class="mcp-server-toggle">
                <span>Enabled</span>
                <el-switch v-model="serverEnabledDraft" />
              </label>
              <el-button :icon="Connection" :loading="testing" @click="testServer(selectedServer)">Test</el-button>
              <el-button
                type="primary"
                :disabled="!serverConfigDirty"
                :loading="saving"
                @click="saveServerSettings"
              >
                Save changes
              </el-button>
            </div>
          </header>

          <dl class="mcp-properties">
            <div><dt>Transport</dt><dd>{{ selectedServer.config.transport }}</dd></div>
            <div><dt>Protocol</dt><dd>{{ selectedServer.protocolVersion || '-' }}</dd></div>
            <div><dt>Connect timeout</dt><dd>{{ selectedServer.config.connectTimeoutSeconds }}s</dd></div>
            <div><dt>Request timeout</dt><dd>{{ selectedServer.config.requestTimeoutSeconds }}s</dd></div>
            <div><dt>Enabled</dt><dd>{{ serverEnabledDraft ? 'Yes' : 'No' }}</dd></div>
          </dl>

          <section v-if="selectedServer.overriddenSources.length" class="mcp-detail-section">
            <h4>Overrides</h4>
            <p>{{ selectedServer.overriddenSources.join(', ') }}</p>
          </section>

          <section v-if="selectedServer.error" class="mcp-detail-section mcp-server-error">
            <h4>Connection error</h4>
            <pre>{{ selectedServer.error }}</pre>
          </section>

          <section class="mcp-detail-section">
            <div class="mcp-section-heading">
              <div>
                <h4>Tools</h4>
                <p>{{ toolSelectionDraft.length }} of {{ availableToolNames.length }} enabled</p>
              </div>
              <div class="mcp-detail-actions">
                <el-button text :disabled="!availableToolNames.length" @click="selectAllTools">Select all</el-button>
                <el-button text :disabled="!availableToolNames.length" @click="clearAllTools">Clear all</el-button>
              </div>
            </div>
            <el-checkbox-group
              v-if="availableToolNames.length"
              v-model="toolSelectionDraft"
              class="mcp-tool-checklist"
            >
              <div v-for="tool in availableToolDetails" :key="tool.name" class="mcp-tool-item">
                <div class="mcp-tool-item-main">
                  <el-checkbox :value="tool.name">
                    <code>{{ tool.name }}</code>
                  </el-checkbox>
                  <el-tag v-if="selectedServer.tools.includes(tool.name)" size="small" type="success">loaded</el-tag>
                  <el-button
                    size="small"
                    :icon="VideoPlay"
                    :disabled="!tool.discovered"
                    @click="openToolDebugger(tool)"
                  >
                    Test
                  </el-button>
                </div>
                <p>{{ tool.description || 'No description provided.' }}</p>
              </div>
            </el-checkbox-group>
            <p v-else>No tools discovered. Test the server to load its tool list.</p>
          </section>

          <section class="mcp-detail-section">
            <h4>Headers</h4>
            <ul v-if="headerEntries(selectedServer.config).length" class="mcp-headers-list">
              <li v-for="header in headerEntries(selectedServer.config)" :key="header[0]">
                <code>{{ header[0] }}</code><span>{{ header[1] }}</span>
              </li>
            </ul>
            <p v-else>No custom headers.</p>
          </section>
        </template>
        <div v-else class="mcp-empty-detail">Select a server to inspect its configuration.</div>
      </main>
    </div>

    <el-dialog
      v-model="jsonDialogOpen"
      :title="hasDatabaseConfig ? 'Edit mcp.json' : 'Create mcp.json'"
      width="min(720px, calc(100vw - 24px))"
    >
      <p class="mcp-json-help">
        Paste or edit the complete {{ scopeLabel }} <code>mcp.json</code>. Saving replaces the whole file.
      </p>
      <el-input
        v-model="jsonDraft"
        class="mcp-json-editor"
        type="textarea"
        :rows="18"
        :placeholder="jsonPlaceholder"
        spellcheck="false"
      />
      <div v-if="jsonError" class="mcp-error">{{ jsonError }}</div>
      <template #footer>
        <div class="mcp-dialog-footer">
          <el-button @click="jsonDialogOpen = false">Cancel</el-button>
          <el-button type="primary" :loading="saving" @click="saveJsonConfig">Save mcp.json</el-button>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="debugDialogOpen"
      class="mcp-debug-dialog"
      :title="debugTool ? `Test ${debugTool.name}` : 'Test MCP tool'"
      top="20px"
      width="min(760px, calc(100vw - 24px))"
    >
      <div v-if="debugTool" class="mcp-debugger">
        <p>{{ debugTool.description || 'No description provided.' }}</p>
        <div class="mcp-debug-field">
          <strong>Input schema</strong>
          <pre>{{ formatJson(debugTool.inputSchema || {}) }}</pre>
        </div>
        <div class="mcp-debug-field">
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
          <el-button @click="debugDialogOpen = false">Close</el-button>
          <el-button type="primary" :icon="VideoPlay" :loading="debugging" @click="runToolDebug">
            Run tool
          </el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { Connection, Delete, DocumentAdd, EditPen, Refresh, VideoPlay } from '@element-plus/icons-vue'
import { ElCheckbox, ElCheckboxGroup, ElMessage, ElSwitch } from 'element-plus'
import { request } from '../api/http'

const props = defineProps({
  sessionId: { type: String, default: '' },
  scope: {
    type: String,
    default: 'global',
    validator: (value) => ['global', 'project'].includes(value)
  }
})

const emit = defineEmits(['changed'])
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const debugging = ref(false)
const error = ref('')
const jsonError = ref('')
const servers = ref([])
const databaseConfig = ref(null)
const selectedName = ref('')
const jsonDialogOpen = ref(false)
const jsonDraft = ref('')
const serverEnabledDraft = ref(true)
const originalServerEnabled = ref(true)
const toolSelectionDraft = ref([])
const originalToolSelection = ref([])
const debugDialogOpen = ref(false)
const debugTool = ref(null)
const debugArguments = ref('{}')
const debugResult = ref('')
const debugError = ref('')

const jsonPlaceholder = JSON.stringify({
  mcpServers: {
    catalog: {
      transport: 'streamable-http',
      url: 'https://example.com/mcp',
      enabled: true,
      headers: {
        Authorization: 'Bearer ${CATALOG_MCP_TOKEN}'
      },
      connectTimeoutSeconds: 10,
      requestTimeoutSeconds: 60
    }
  }
}, null, 2)

const selectedServer = computed(() => servers.value.find((server) => server.name === selectedName.value) || null)
const availableCount = computed(() => servers.value.filter((server) => server.status === 'available').length)
const hasDatabaseConfig = computed(() => databaseConfig.value !== null)
const scopeLabel = computed(() => props.scope === 'project' ? 'project' : 'global')
const availableToolDetails = computed(() => {
  const details = new Map()
  for (const tool of selectedServer.value?.toolDetails || []) {
    details.set(tool.name, { ...tool, discovered: true })
  }
  for (const name of selectedServer.value?.availableTools || []) {
    if (!details.has(name)) details.set(name, { name, description: '', inputSchema: {}, discovered: true })
  }
  for (const name of selectedServer.value?.config?.enabledTools || []) {
    if (!details.has(name)) details.set(name, { name, description: '', inputSchema: {}, discovered: false })
  }
  return Array.from(details.values()).sort((left, right) => left.name.localeCompare(right.name))
})
const availableToolNames = computed(() => availableToolDetails.value.map((tool) => tool.name))
const serverConfigDirty = computed(() => (
  serverEnabledDraft.value !== originalServerEnabled.value
  || !sameToolSelection(toolSelectionDraft.value, originalToolSelection.value)
))

watch(() => [props.sessionId, props.scope], load)
watch(selectedName, syncServerDraft)
onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await request(`/api/mcp/config${sessionQuery()}`)
    servers.value = data?.servers || []
    databaseConfig.value = data?.databaseConfig ?? null
    if (!servers.value.some((server) => server.name === selectedName.value)) {
      selectedName.value = servers.value[0]?.name || ''
    }
    syncServerDraft()
  } catch (reason) {
    error.value = reason.message
  } finally {
    loading.value = false
  }
}

function openJsonEditor() {
  jsonDraft.value = databaseConfig.value || ''
  jsonError.value = ''
  jsonDialogOpen.value = true
}

async function saveJsonConfig() {
  jsonError.value = ''
  let document
  try {
    document = JSON.parse(jsonDraft.value)
  } catch (reason) {
    jsonError.value = `Invalid JSON: ${reason.message}`
    return
  }

  const importedServers = document?.mcpServers
  if (!isPlainObject(importedServers)) {
    jsonError.value = 'The JSON must contain an mcpServers object.'
    return
  }
  for (const [name, config] of Object.entries(importedServers)) {
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(name)) {
      jsonError.value = `Invalid server name: ${name}`
      return
    }
    if (!isPlainObject(config) || typeof config.url !== 'string' || !config.url.trim()) {
      jsonError.value = `Server ${name} must define a URL.`
      return
    }
    if (config.transport !== 'streamable-http') {
      jsonError.value = `Server ${name} must use transport streamable-http.`
      return
    }
  }

  saving.value = true
  try {
    const data = await request(`/api/mcp/config${sessionQuery()}`, {
      method: 'PUT',
      body: JSON.stringify({ content: jsonDraft.value })
    })
    applySaved(data)
    if (!servers.value.some((server) => server.name === selectedName.value)) {
      selectedName.value = Object.keys(importedServers)[0] || servers.value[0]?.name || ''
    }
    jsonDialogOpen.value = false
    ElMessage.success('mcp.json saved. Changes apply on the next agent run.')
  } catch (reason) {
    jsonError.value = reason.message
  } finally {
    saving.value = false
  }
}

async function deleteDatabaseConfig() {
  saving.value = true
  error.value = ''
  try {
    const data = await request(`/api/mcp/config${sessionQuery()}`, {
      method: 'DELETE'
    })
    applySaved(data)
    ElMessage.success(`${scopeLabel.value} mcp.json deleted. Changes apply on the next agent run.`)
  } catch (reason) {
    error.value = reason.message
  } finally {
    saving.value = false
  }
}

async function testServer(server) {
  await runTest(server.name, server.config)
}

async function runTest(name, config) {
  testing.value = true
  error.value = ''
  try {
    const result = await request('/api/mcp/test', {
      method: 'POST',
      body: JSON.stringify({ name, config })
    })
    if (result.success) {
      const server = servers.value.find((item) => item.name === name)
      if (server) {
        server.availableTools = result.tools || []
        server.toolDetails = result.toolDetails || []
      }
      syncServerDraft()
      ElMessage.success(`Connected. ${result.tools.length} tools discovered.`)
    } else {
      error.value = result.error || 'Connection failed.'
    }
    return result
  } catch (reason) {
    error.value = reason.message
    return null
  } finally {
    testing.value = false
  }
}

function applySaved(data) {
  servers.value = preserveToolCatalogs(servers.value, data?.servers || [])
  databaseConfig.value = data?.databaseConfig ?? null
  syncServerDraft()
  emit('changed')
}

function preserveToolCatalogs(previousServers, nextServers) {
  const previousByName = new Map(previousServers.map((server) => [server.name, server]))
  return nextServers.map((server) => {
    const previous = previousByName.get(server.name)
    if (!previous || (server.availableTools || []).length || (server.toolDetails || []).length) {
      return server
    }
    return {
      ...server,
      availableTools: [...(previous.availableTools || [])],
      toolDetails: [...(previous.toolDetails || [])]
    }
  })
}

function syncServerDraft() {
  const server = selectedServer.value
  if (!server) {
    serverEnabledDraft.value = true
    originalServerEnabled.value = true
    toolSelectionDraft.value = []
    originalToolSelection.value = []
    return
  }
  serverEnabledDraft.value = server.config?.enabled !== false
  originalServerEnabled.value = serverEnabledDraft.value
  const selection = server.config?.enabledTools == null
    ? [...availableToolNames.value]
    : [...server.config.enabledTools]
  toolSelectionDraft.value = normalizeToolSelection(selection)
  originalToolSelection.value = normalizeToolSelection(selection)
}

function selectAllTools() {
  toolSelectionDraft.value = [...availableToolNames.value]
}

function clearAllTools() {
  toolSelectionDraft.value = []
}

async function saveServerSettings() {
  const server = selectedServer.value
  if (!server || !serverConfigDirty.value) return
  let document = { mcpServers: {} }
  if (databaseConfig.value) {
    try {
      document = JSON.parse(databaseConfig.value)
    } catch (reason) {
      error.value = `Invalid stored mcp.json: ${reason.message}`
      return
    }
  }
  if (!isPlainObject(document.mcpServers)) document.mcpServers = {}
  const config = JSON.parse(JSON.stringify(server.config || {}))
  config.enabled = serverEnabledDraft.value
  const toolSelectionChanged = !sameToolSelection(toolSelectionDraft.value, originalToolSelection.value)
  if (server.config?.enabledTools == null && !toolSelectionChanged) {
    delete config.enabledTools
  } else {
    config.enabledTools = normalizeToolSelection(toolSelectionDraft.value)
  }
  document.mcpServers[server.name] = config
  saving.value = true
  error.value = ''
  try {
    const data = await request(`/api/mcp/config${sessionQuery()}`, {
      method: 'PUT',
      body: JSON.stringify({ content: `${JSON.stringify(document, null, 2)}\n` })
    })
    applySaved(data)
    ElMessage.success('MCP server settings saved. Changes apply on the next agent run.')
  } catch (reason) {
    error.value = reason.message
  } finally {
    saving.value = false
  }
}

function openToolDebugger(tool) {
  debugTool.value = tool
  debugArguments.value = '{}'
  debugResult.value = ''
  debugError.value = ''
  debugDialogOpen.value = true
}

async function runToolDebug() {
  if (!selectedServer.value || !debugTool.value || debugging.value) return
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
    const response = await request('/api/mcp/call', {
      method: 'POST',
      body: JSON.stringify({
        name: selectedServer.value.name,
        config: selectedServer.value.config,
        toolName: debugTool.value.name,
        arguments: args
      })
    })
    debugResult.value = response?.result == null ? '' : formatJson(response.result)
    if (!response?.success) {
      debugError.value = response?.error || 'MCP tool call failed.'
    }
  } catch (reason) {
    debugError.value = reason.message
  } finally {
    debugging.value = false
  }
}

function normalizeToolSelection(values) {
  return Array.from(new Set(values || [])).sort()
}

function sameToolSelection(left, right) {
  const normalizedLeft = normalizeToolSelection(left)
  const normalizedRight = normalizeToolSelection(right)
  return normalizedLeft.length === normalizedRight.length
    && normalizedLeft.every((value, index) => value === normalizedRight[index])
}

function formatJson(value) {
  return JSON.stringify(value, null, 2)
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function headerEntries(config) {
  return Object.entries(config?.headers || {})
}

function sessionQuery() {
  const query = new URLSearchParams({ scope: props.scope })
  if (props.scope === 'project' && props.sessionId) query.set('sessionId', props.sessionId)
  return `?${query.toString()}`
}

function statusLabel(status) {
  return ({ available: 'Available', unavailable: 'Unavailable', disabled: 'Disabled', not_loaded: 'Not loaded' })[status] || status
}

function statusType(status) {
  return ({ available: 'success', unavailable: 'danger', disabled: 'info', not_loaded: 'warning' })[status] || 'info'
}

function hasUnsavedChanges() {
  const storedJson = databaseConfig.value || ''
  return serverConfigDirty.value || (jsonDialogOpen.value && jsonDraft.value !== storedJson)
}

function discardChanges() {
  syncServerDraft()
  jsonDraft.value = databaseConfig.value || ''
  jsonError.value = ''
  jsonDialogOpen.value = false
}

defineExpose({ hasUnsavedChanges, discardChanges })
</script>
