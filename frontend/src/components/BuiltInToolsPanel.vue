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
        <p>{{ selection.length }} of {{ tools.length }} built-in tools enabled</p>
      </div>
      <div class="built-in-tools-actions">
        <el-button text :disabled="loading || !tools.length" @click="selectAll">Select all</el-button>
        <el-button text :disabled="loading || !tools.length" @click="clearAll">Clear all</el-button>
        <el-popconfirm
          title="Delete tools.json and enable every built-in tool?"
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

    <el-checkbox-group v-else v-model="selection" class="built-in-tool-list">
      <div v-for="tool in tools" :key="tool.name" class="built-in-tool-row">
        <div class="built-in-tool-main">
          <el-checkbox :value="tool.name">
            <code>{{ tool.name }}</code>
          </el-checkbox>
          <el-button size="small" :icon="VideoPlay" @click="openDebugger(tool)">Test</el-button>
        </div>
        <p>{{ tool.description || 'No description provided.' }}</p>
      </div>
      <div v-if="!tools.length" class="built-in-tools-empty">No built-in tools are registered.</div>
    </el-checkbox-group>

    <el-dialog
      v-model="debugOpen"
      class="mcp-debug-dialog"
      :title="debugTool ? `Test ${debugTool.name}` : 'Test built-in tool'"
      top="20px"
      width="min(760px, calc(100vw - 24px))"
    >
      <div v-if="debugTool" class="mcp-debugger">
        <p>{{ debugTool.description || 'No description provided.' }}</p>
        <div class="mcp-debug-field">
          <strong>Input schema</strong>
          <pre>{{ formatJson(debugTool.parametersSchema || {}) }}</pre>
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
          <el-button @click="debugOpen = false">Close</el-button>
          <el-button type="primary" :icon="VideoPlay" :loading="debugging" @click="runDebug">
            Run tool
          </el-button>
        </div>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Loading, RefreshLeft, VideoPlay } from '@element-plus/icons-vue'
import { ElCheckbox, ElCheckboxGroup, ElMessage } from 'element-plus'
import { request } from '../api/http'

const props = defineProps({
  sessionId: { type: String, default: '' }
})

const emit = defineEmits(['changed'])
const tools = ref([])
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
    applyResponse(await request('/api/tools/config'))
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
    applyResponse(await request('/api/tools/config', {
      method: 'PUT',
      body: JSON.stringify({ enabledTools: selection.value })
    }))
    ElMessage.success('Built-in tool selection saved.')
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
    applyResponse(await request('/api/tools/config', { method: 'DELETE' }))
    ElMessage.success('All built-in tools are enabled by default.')
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

function openDebugger(tool) {
  debugTool.value = tool
  debugArguments.value = '{}'
  debugResult.value = ''
  debugError.value = ''
  debugOpen.value = true
}

async function runDebug() {
  if (!debugTool.value || debugging.value) return
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
    const response = await request('/api/tools/call', {
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
  return dirty.value
}

function discardChanges() {
  selection.value = [...originalSelection.value]
}

defineExpose({ hasUnsavedChanges, discardChanges })
</script>
