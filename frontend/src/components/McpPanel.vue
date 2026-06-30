<template>
  <section class="mcp-panel">
    <header class="mcp-page-header">
      <div>
        <h2>MCP Management</h2>
        <p>{{ servers.length }} servers, {{ availableCount }} available</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">Add server</el-button>
    </header>

    <div v-if="restartRequired" class="mcp-notice">
      Database configuration was saved. Restart this application instance to activate the changes.
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
              <el-button :icon="Connection" :loading="testing" @click="testServer(selectedServer)">Test</el-button>
              <el-button :icon="EditPen" @click="openEdit(selectedServer)">
                {{ selectedServer.source === 'database' ? 'Edit' : 'Override' }}
              </el-button>
              <el-button
                v-if="databaseServers[selectedServer.name]"
                type="danger"
                plain
                :icon="Delete"
                :loading="saving"
                @click="removeDatabaseServer(selectedServer.name)"
              >
                Delete
              </el-button>
            </div>
          </header>

          <dl class="mcp-properties">
            <div><dt>Transport</dt><dd>{{ selectedServer.config.transport }}</dd></div>
            <div><dt>Protocol</dt><dd>{{ selectedServer.protocolVersion || '-' }}</dd></div>
            <div><dt>Connect timeout</dt><dd>{{ selectedServer.config.connectTimeoutSeconds }}s</dd></div>
            <div><dt>Request timeout</dt><dd>{{ selectedServer.config.requestTimeoutSeconds }}s</dd></div>
            <div><dt>Enabled</dt><dd>{{ selectedServer.config.enabled ? 'Yes' : 'No' }}</dd></div>
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
            <h4>Tools</h4>
            <ul v-if="selectedServer.tools.length" class="mcp-tools-list">
              <li v-for="tool in selectedServer.tools" :key="tool"><code>{{ tool }}</code></li>
            </ul>
            <p v-else>No tools loaded for this application instance.</p>
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
      v-model="dialogOpen"
      :title="editingName ? 'Edit MCP server' : 'Add MCP server'"
      width="min(620px, calc(100vw - 24px))"
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="Server name">
          <el-input v-model="draft.name" :disabled="Boolean(editingName)" placeholder="catalog" />
        </el-form-item>
        <el-form-item label="URL">
          <el-input v-model="draft.url" placeholder="https://example.com/mcp" />
        </el-form-item>
        <div class="mcp-form-grid">
          <el-form-item label="Connect timeout (seconds)">
            <el-input v-model="draft.connectTimeoutSeconds" type="number" min="1" max="3600" />
          </el-form-item>
          <el-form-item label="Request timeout (seconds)">
            <el-input v-model="draft.requestTimeoutSeconds" type="number" min="1" max="3600" />
          </el-form-item>
        </div>
        <label class="mcp-checkbox"><input v-model="draft.enabled" type="checkbox" /> Enabled</label>

        <div class="mcp-headers-editor">
          <div class="mcp-headers-editor-title">
            <strong>Headers</strong>
            <el-button text :icon="Plus" @click="addHeader">Add header</el-button>
          </div>
          <div v-for="(header, index) in draft.headers" :key="index" class="mcp-header-row">
            <el-input v-model="header.name" placeholder="Authorization" />
            <el-input v-model="header.value" placeholder="Bearer ${MCP_TOKEN}" />
            <el-button text :icon="Delete" title="Remove header" @click="draft.headers.splice(index, 1)" />
          </div>
          <p>Secret header values must reference environment variables such as <code>${MCP_TOKEN}</code>.</p>
        </div>
        <div v-if="dialogError" class="mcp-error">{{ dialogError }}</div>
      </el-form>
      <template #footer>
        <div class="mcp-dialog-footer">
          <el-button @click="dialogOpen = false">Cancel</el-button>
          <el-button :loading="testing" @click="testDraft">Test connection</el-button>
          <el-button type="primary" :loading="saving" @click="saveDraft">Save database config</el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { Connection, Delete, EditPen, Plus, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { request } from '../api/http'

const props = defineProps({
  sessionId: { type: String, default: '' }
})

const emit = defineEmits(['changed'])
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const error = ref('')
const dialogError = ref('')
const servers = ref([])
const databaseServers = ref({})
const databaseContentHash = ref(null)
const restartRequired = ref(false)
const selectedName = ref('')
const dialogOpen = ref(false)
const editingName = ref('')
const draft = ref(emptyDraft())

const selectedServer = computed(() => servers.value.find((server) => server.name === selectedName.value) || null)
const availableCount = computed(() => servers.value.filter((server) => server.status === 'available').length)

watch(() => props.sessionId, load)
onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await request(`/api/mcp/config${sessionQuery()}`)
    servers.value = data?.servers || []
    databaseServers.value = data?.databaseServers || {}
    databaseContentHash.value = data?.databaseContentHash ?? null
    restartRequired.value = Boolean(data?.restartRequired) || restartRequired.value
    if (!servers.value.some((server) => server.name === selectedName.value)) {
      selectedName.value = servers.value[0]?.name || ''
    }
  } catch (reason) {
    error.value = reason.message
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingName.value = ''
  draft.value = emptyDraft()
  dialogError.value = ''
  dialogOpen.value = true
}

function openEdit(server) {
  editingName.value = server.name
  draft.value = fromServer(server.name, databaseServers.value[server.name] || server.config)
  dialogError.value = ''
  dialogOpen.value = true
}

function addHeader() {
  draft.value.headers.push({ name: '', value: '' })
}

async function saveDraft() {
  dialogError.value = ''
  const name = draft.value.name.trim()
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(name) || !draft.value.url.trim()) {
    dialogError.value = 'A valid server name and URL are required.'
    return
  }
  const nextServers = { ...databaseServers.value, [name]: toConfig(draft.value) }
  saving.value = true
  try {
    const data = await request(`/api/mcp/config${sessionQuery()}`, {
      method: 'PUT',
      body: JSON.stringify({
        expectedContentHash: databaseContentHash.value,
        mcpServers: nextServers
      })
    })
    applySaved(data)
    selectedName.value = name
    dialogOpen.value = false
    ElMessage.success('MCP configuration saved. Restart required.')
  } catch (reason) {
    dialogError.value = reason.message
  } finally {
    saving.value = false
  }
}

async function removeDatabaseServer(name) {
  if (!window.confirm(`Delete the database configuration for ${name}?`)) return
  const nextServers = { ...databaseServers.value }
  delete nextServers[name]
  saving.value = true
  error.value = ''
  try {
    const data = await request(`/api/mcp/config${sessionQuery()}`, {
      method: 'PUT',
      body: JSON.stringify({ expectedContentHash: databaseContentHash.value, mcpServers: nextServers })
    })
    applySaved(data)
    ElMessage.success('Database configuration deleted. Restart required.')
  } catch (reason) {
    error.value = reason.message
  } finally {
    saving.value = false
  }
}

async function testServer(server) {
  await runTest(server.name, databaseServers.value[server.name] || server.config)
}

async function testDraft() {
  dialogError.value = ''
  const name = draft.value.name.trim()
  if (!name || !draft.value.url.trim()) {
    dialogError.value = 'A server name and URL are required before testing.'
    return
  }
  const result = await runTest(name, toConfig(draft.value), true)
  if (!result?.success) dialogError.value = result?.error || 'Connection failed.'
}

async function runTest(name, config, fromDialog = false) {
  testing.value = true
  if (!fromDialog) error.value = ''
  try {
    const result = await request('/api/mcp/test', {
      method: 'POST',
      body: JSON.stringify({ name, config })
    })
    if (result.success) {
      ElMessage.success(`Connected. ${result.tools.length} tools discovered.`)
    } else if (!fromDialog) {
      error.value = result.error || 'Connection failed.'
    }
    return result
  } catch (reason) {
    if (fromDialog) dialogError.value = reason.message
    else error.value = reason.message
    return null
  } finally {
    testing.value = false
  }
}

function applySaved(data) {
  servers.value = data?.servers || []
  databaseServers.value = data?.databaseServers || {}
  databaseContentHash.value = data?.databaseContentHash ?? null
  restartRequired.value = true
  emit('changed')
}

function emptyDraft() {
  return {
    name: '',
    url: '',
    enabled: true,
    connectTimeoutSeconds: 10,
    requestTimeoutSeconds: 60,
    headers: []
  }
}

function fromServer(name, config) {
  return {
    name,
    url: config?.url || '',
    enabled: config?.enabled !== false,
    connectTimeoutSeconds: config?.connectTimeoutSeconds || 10,
    requestTimeoutSeconds: config?.requestTimeoutSeconds || 60,
    headers: Object.entries(config?.headers || {}).map(([headerName, value]) => ({ name: headerName, value }))
  }
}

function toConfig(value) {
  const headers = {}
  for (const header of value.headers) {
    if (header.name.trim()) headers[header.name.trim()] = header.value
  }
  return {
    transport: 'streamable-http',
    url: value.url.trim(),
    enabled: Boolean(value.enabled),
    headers,
    connectTimeoutSeconds: Number(value.connectTimeoutSeconds) || 10,
    requestTimeoutSeconds: Number(value.requestTimeoutSeconds) || 60
  }
}

function headerEntries(config) {
  return Object.entries(config?.headers || {})
}

function sessionQuery() {
  return props.sessionId ? `?sessionId=${encodeURIComponent(props.sessionId)}` : ''
}

function statusLabel(status) {
  return ({ available: 'Available', unavailable: 'Unavailable', disabled: 'Disabled', not_loaded: 'Not loaded' })[status] || status
}

function statusType(status) {
  return ({ available: 'success', unavailable: 'danger', disabled: 'info', not_loaded: 'warning' })[status] || 'info'
}
</script>
