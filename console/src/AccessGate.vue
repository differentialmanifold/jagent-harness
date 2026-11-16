<template>
  <div v-if="ready" class="access-root">
    <div v-if="insecure" class="transport-notice" role="status">HTTP connection: credentials, messages and tool commands are not encrypted. Use a trusted network or HTTPS.</div>
    <App :key="userId" />
  </div>
  <main v-else class="access-welcome">
    <section>
      <span class="access-brand">JAgentHarness</span>
      <h1>{{ checking ? 'Connecting to your workspace' : 'Your mainbot is waiting' }}</h1>
      <p>{{ message || 'Start your local Runner with the user credentials provided by your Harness. It will open this console automatically.' }}</p>
      <p class="access-hint">Your Agents, projects and conversations appear while your local Runner is connected.</p>
      <button class="access-refresh" @click="refresh">Check connection</button>
    </section>
  </main>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import App from './App.vue'
import { setStorageUser } from './utils/storage.js'
const ready = ref(false), checking = ref(true), insecure = ref(false), userId = ref(''), message = ref('')
let timer, pending = false, disposed = false
async function refresh() {
  if (pending) return
  pending = true
  try {
    const response = await fetch('/api/v1/access/status', { cache: 'no-store' })
    if (response.status === 404) { ready.value = true; return } // Host applications may disable personal access.
    if (!response.ok) throw new Error('Harness is unavailable. Check your connection and try again.')
    const status = await response.json()
    if (disposed) return
    ready.value = Boolean(status.authenticated)
    if (ready.value) {
      setStorageUser(status.user.userId)
      userId.value = status.user.userId
      insecure.value = status.insecure && !['localhost', '127.0.0.1', '[::1]'].includes(location.hostname)
      message.value = ''
    }
  } catch (error) { ready.value = false; message.value = error.message }
  finally { checking.value = false; pending = false }
}
async function connect() {
  const fragment = new URLSearchParams(location.hash.slice(1))
  const ticket = fragment.get('jagent-ticket')
  if (ticket) {
    history.replaceState(null, '', location.pathname + location.search)
    try {
      const response = await fetch('/api/v1/access/exchange', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-JAgent-Console': '1' }, body: JSON.stringify({ ticket }) })
      if (!response.ok) { const error = await response.json(); throw new Error(error.message || 'This console link expired. Restart the Runner to open a fresh link.') }
    } catch (error) { message.value = error.message }
  }
  await refresh()
}
onMounted(async () => {
  window.addEventListener('hashchange', connect)
  await connect()
  if (!disposed) timer = setInterval(refresh, 3000)
})
onUnmounted(() => { disposed = true; clearInterval(timer); window.removeEventListener('hashchange', connect) })
</script>

<style scoped>
.access-root { height: 100%; display: flex; flex-direction: column; }
.access-root :deep(.app-shell) { flex: 1; min-height: 0; height: auto; }
.transport-notice { padding: 8px 20px; background: #fff5db; color: #795213; font-size: 13px; border-bottom: 1px solid #eedcb0; }
.access-welcome { min-height: 100vh; display: grid; place-items: center; padding: 32px; background: #f7f9fc; color: #1d2939; box-sizing: border-box; }
.access-welcome section { max-width: 520px; }
.access-brand { font-weight: 600; color: #2563eb; }
.access-welcome h1 { font-size: 30px; margin: 18px 0; }
.access-welcome p { line-height: 1.7; color: #536174; }
.access-hint { font-size: 14px; }
.access-refresh { margin-top: 12px; padding: 10px 16px; background: #fff; border: 1px solid #d0d5dd; border-radius: 8px; color: #344054; cursor: pointer; font: inherit; }
</style>
