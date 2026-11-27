<template>
  <section class="runners-settings">
    <div class="directory-actions">
      <el-checkbox v-model="includeHidden" @change="load">Show removed</el-checkbox>
      <el-button :loading="loading" @click="load">Refresh</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-empty v-if="!loading && !runners.length && !error" description="No Runners to display." />
    <article v-for="runner in runners" :key="runner.runnerId">
      <header>
        <h3>{{ runner.name }}</h3>
        <el-tag v-if="runner.runnerId === mainbotRunnerId" effect="plain">mainbot Runner</el-tag>
        <el-tag :type="runner.online ? 'success' : 'info'">{{ runner.hidden ? 'Removed' : runner.online ? 'Online' : 'Offline' }}</el-tag>
      </header>
      <p>{{ runner.platform || runner.runtime }}</p>
      <p v-if="runner.description">{{ runner.description }}</p>
      <el-tag v-for="capability in runner.capabilities" :key="capability" effect="plain">{{ capability }}</el-tag>
      <footer>
        <el-button size="small" @click="inspect(runner)">Details</el-button>
        <el-button v-if="runner.hidden || (!runner.online && runner.runnerId !== mainbotRunnerId)"
          size="small" :loading="pending === runner.runnerId" @click="setHidden(runner, !runner.hidden)">
          {{ runner.hidden ? 'Restore' : 'Remove from list' }}
        </el-button>
      </footer>
    </article>
    <el-dialog v-model="detailOpen" :title="detailName" width="min(520px, 92vw)">
      <p v-if="detailError" role="alert">{{ detailError }}</p>
      <p v-else-if="!detail">Loading Runner details…</p>
      <dl v-else class="runner-detail">
        <dt>Runner ID</dt><dd>{{ detail.runnerId }}</dd>
        <dt>Version</dt><dd>{{ detail.version || 'Unknown' }}</dd>
        <template v-if="detail.buildRevision"><dt>Build</dt><dd>{{ detail.buildRevision }}</dd></template>
        <template v-if="detail.protocolVersion"><dt>Protocol</dt><dd>{{ detail.protocolVersion }}</dd></template>
        <dt>Workspace</dt><dd>{{ detail.workspaces ? (detail.workspaceRoot || 'Selected per project') : 'Not required' }}</dd>
        <dt>Tools</dt><dd>{{ detail.tools.map(t => t.name).join(', ') }}</dd>
      </dl>
    </el-dialog>
  </section>
</template>
<script setup>
import { onMounted, ref } from 'vue'
import { request } from '../api/http.js'

const runners = ref([]), mainbotRunnerId = ref(''), includeHidden = ref(false)
const loading = ref(false), error = ref(''), pending = ref('')
const detailOpen = ref(false), detailName = ref(''), detail = ref(null), detailError = ref('')
let loadVersion = 0, detailVersion = 0
async function load() {
  const version = ++loadVersion
  loading.value = true
  error.value = ''
  try {
    const [rows, defaults] = await Promise.all([
      request('/api/v1/runners' + (includeHidden.value ? '?includeHidden=true' : '')),
      request('/api/v1/personal/defaults').catch(() => null),
    ])
    if (version !== loadVersion) return
    runners.value = rows
    mainbotRunnerId.value = defaults?.mainbotRunnerId || ''
  } catch (e) {
    if (version === loadVersion) error.value = e.message
  } finally {
    if (version === loadVersion) loading.value = false
  }
}
async function inspect(runner) {
  const version = ++detailVersion
  detailOpen.value = true
  detailName.value = runner.name
  detail.value = null
  detailError.value = ''
  try {
    const result = await request('/api/v1/runners/' + encodeURIComponent(runner.runnerId))
    if (version === detailVersion) detail.value = result
  } catch (e) {
    if (version === detailVersion) detailError.value = e.message
  }
}
async function setHidden(runner, hidden) {
  pending.value = runner.runnerId
  error.value = ''
  try {
    await request('/api/v1/runners/' + encodeURIComponent(runner.runnerId) + '/visibility', {
      method: 'PUT', body: JSON.stringify({ hidden }),
    })
    await load()
  } catch (e) { error.value = e.message }
  finally { pending.value = '' }
}
onMounted(load)
</script>
<style scoped>
.runners-settings { height: 100%; overflow: auto; padding: 24px; box-sizing: border-box; }
.directory-actions, article header, article footer { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.directory-actions { justify-content: space-between; }
article { border: 1px solid var(--el-border-color); border-radius: 12px; padding: 20px; margin-top: 16px; overflow-wrap: anywhere; }
h3 { margin: 0; flex: 1; }
.el-tag { margin-right: 6px; }
footer { margin-top: 14px; }
.runner-detail { display: grid; grid-template-columns: auto 1fr; gap: 10px 16px; }
dt { color: var(--el-text-color-secondary); }
dd { margin: 0; overflow-wrap: anywhere; }
</style>
