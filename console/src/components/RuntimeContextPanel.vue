<template>
  <section class="runtime-context-panel">
    <el-empty v-if="!sessionId" description="Select or create a chat to view its runtime context." />
    <template v-else>
    <h3>{{ title || 'Current conversation' }}</h3>
    <p>Available resources for this conversation under the current configuration. Skills are listed for discovery; their presence does not mean the model has read them.</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <el-button :loading="loading" @click="load">Refresh</el-button>
    <InspectorPanel :key="sessionId" :session-id="sessionId" :provider="provider" :tools="context?.tools || []" :agent-context="context" />
    </template>
  </section>
</template>
<script setup>
import { ref, watch } from 'vue'
import { request } from '../api/http'
import InspectorPanel from './InspectorPanel.vue'
const props = defineProps({ sessionId: String, title: String })
const context = ref(null), provider = ref(null), loading = ref(false), error = ref('')
let version = 0
async function load() {
  const current = ++version
  context.value = null
  if (!props.sessionId) return
  loading.value = true
  error.value = ''
  try {
    const [info, model] = await Promise.all([
      request('/api/v1/agent/context', { method: 'POST', body: JSON.stringify({ sessionId: props.sessionId }) }),
      request('/api/v1/providers')
    ])
    if (current !== version) return
    context.value = info
    provider.value = model
  } catch (e) { if (current === version) error.value = e.message }
  finally { if (current === version) loading.value = false }
}
watch(() => props.sessionId, load, { immediate: true })
</script>
<style scoped>
.runtime-context-panel { min-width: 0; height: 100%; overflow: auto; padding: 20px; }
h3 { margin-top: 0; overflow-wrap: anywhere; }
p { color: #64748b; font-size: 13px; line-height: 1.6; }
:deep(.inspector) { height: min(560px, 65vh); margin-top: 16px; border: 0; }
:deep(.inspector-section), :deep(.inspector-tabs) { min-width: 0; }
:deep(.final-prompt-panel) { padding: 0; }
</style>
