<template>
  <section class="final-prompt-panel">
    <div class="github-file-toolbar">
      <el-radio-group v-model="viewTab" size="small" aria-label="Final prompt view">
        <el-radio-button value="preview">Preview</el-radio-button>
        <el-radio-button value="code">Code</el-radio-button>
      </el-radio-group>
      <span>{{ lineCount }} lines</span>
      <span>{{ bytes }} bytes</span>
      <code v-if="workspaceRoot" class="prompt-preview-workspace" :title="workspaceRoot">
        {{ workspaceRoot }}
      </code>
      <div class="github-file-toolbar-actions">
        <el-button size="small" :icon="Refresh" :loading="loading" title="Refresh final prompt" @click="load" />
        <el-button size="small" :icon="CopyDocument" title="Copy final prompt" @click="copyPrompt" />
      </div>
    </div>

    <div v-if="error" class="configuration-inline-error">{{ error }}</div>
    <div v-if="loading" class="final-prompt-loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>Loading final prompt</span>
    </div>
    <div
      v-else-if="viewTab === 'preview'"
      class="github-markdown-body prompt-preview-content"
    >
      <template v-if="blocks.length">
        <component
          :is="block.tag"
          v-for="(block, index) in blocks"
          :key="index"
          :class="block.className"
        >
          {{ block.text }}
        </component>
      </template>
      <el-empty v-else description="No prompt available" :image-size="88" />
    </div>
    <pre
      v-else
      class="github-code-view prompt-preview-content"
    ><code>{{ prompt }}</code></pre>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { CopyDocument, Loading, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElRadioButton, ElRadioGroup } from 'element-plus'
import { request } from '../api/http'
import { countLines, markdownBlocks } from '../utils/markdown'

const props = defineProps({
  sessionId: { type: String, default: '' }
})

const viewTab = ref('preview')
const prompt = ref('')
const workspaceRoot = ref('')
const loading = ref(false)
const error = ref('')
let loadVersion = 0

const blocks = computed(() => markdownBlocks(prompt.value))
const lineCount = computed(() => countLines(prompt.value))
const bytes = computed(() => new TextEncoder().encode(prompt.value).length)

watch(() => props.sessionId, load, { immediate: true })

async function load() {
  const version = ++loadVersion
  loading.value = true
  error.value = ''
  try {
    const preview = await request('/api/agent/prompt-preview', {
      method: 'POST',
      body: JSON.stringify(props.sessionId ? { sessionId: props.sessionId } : {})
    })
    if (version !== loadVersion) return
    prompt.value = preview?.systemPrompt || ''
    workspaceRoot.value = preview?.workspaceRoot || ''
  } catch (reason) {
    if (version === loadVersion) error.value = reason.message
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

async function copyPrompt() {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(prompt.value)
    ElMessage.success('Copied final prompt.')
  }
}

defineExpose({
  hasUnsavedChanges: () => false,
  discardChanges: () => {}
})
</script>
