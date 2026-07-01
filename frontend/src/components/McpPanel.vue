<template>
  <section class="mcp-panel">
    <header class="mcp-page-header">
      <div>
        <h2>MCP Management</h2>
        <p>{{ servers.length }} servers, {{ availableCount }} available</p>
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
          title="Delete the database mcp.json?"
          confirm-button-text="Delete"
          cancel-button-text="Cancel"
          @confirm="deleteDatabaseConfig"
        >
          <template #reference>
            <el-button type="danger" plain :icon="Delete" :loading="saving">Delete mcp.json</el-button>
          </template>
        </el-popconfirm>
      </div>
    </header>

    <div v-if="restartRequired" class="mcp-notice">
      Database mcp.json changed. Restart this application instance to activate the changes.
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
      v-model="jsonDialogOpen"
      :title="hasDatabaseConfig ? 'Edit mcp.json' : 'Create mcp.json'"
      width="min(720px, calc(100vw - 24px))"
    >
      <p class="mcp-json-help">
        Paste or edit the complete database <code>mcp.json</code>. Saving replaces the whole file.
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
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { Connection, Delete, DocumentAdd, EditPen, Refresh } from '@element-plus/icons-vue'
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
const jsonError = ref('')
const servers = ref([])
const databaseConfig = ref(null)
const restartRequired = ref(false)
const selectedName = ref('')
const jsonDialogOpen = ref(false)
const jsonDraft = ref('')

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

watch(() => props.sessionId, load)
onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await request(`/api/mcp/config${sessionQuery()}`)
    servers.value = data?.servers || []
    databaseConfig.value = data?.databaseConfig ?? null
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
    ElMessage.success('mcp.json saved. Restart required.')
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
    ElMessage.success('Database mcp.json deleted. Restart required.')
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
  servers.value = data?.servers || []
  databaseConfig.value = data?.databaseConfig ?? null
  restartRequired.value = true
  emit('changed')
}

function isPlainObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
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
