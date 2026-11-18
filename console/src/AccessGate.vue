<template>
  <div v-if="ready" class="access-root">
    <div v-if="insecure" class="transport-notice" role="status">HTTP connection: credentials, messages and tool commands are not encrypted. Use a trusted network or HTTPS.</div>
    <App :key="userId" />
  </div>
  <main v-else class="access-welcome">
    <section>
      <span class="access-brand">JAgentHarness</span>
      <h1>{{ checking ? 'Connecting to your workspace' : authenticated ? 'Waiting for mainbot' : 'Connect to your workspace' }}</h1>
      <p>{{ message || linkError || (authenticated ? 'Start the Runner bound to your mainbot. This page will open automatically when it reconnects.' : 'Approve this browser from the machine running your Runner.') }}</p>
      <p class="access-hint">{{ authenticated ? `Signed in as ${userName}. Your sign-in is retained while mainbot is offline.` : 'You can also open a fresh link from jagent open --link or from System Settings → Devices on a signed-in browser.' }}</p>
      <div v-if="!checking && !authenticated && !message" class="pair-controls">
        <template v-if="pair">
          <p>Run this command on your Runner machine and confirm the code. This page opens automatically after approval.</p>
          <code class="pair-command">jagent pair {{ pair.code }}</code>
          <p class="access-hint">Expires at {{ new Date(pair.expiresAt).toLocaleTimeString() }}.</p>
        </template>
        <button v-else class="access-refresh pair-start" :disabled="starting" @click="startPair">{{ starting ? 'Requesting code…' : 'Pair with a Runner' }}</button>
      </div>
      <button class="access-refresh" :disabled="manualCheck || checking" @click="checkConnection">{{ manualCheck ? 'Checking…' : 'Check connection' }}</button>
      <p role="status" aria-live="polite" class="access-hint">{{ feedback }}</p>
    </section>
  </main>
</template>

<script setup>
import { ref, provide, onMounted, onUnmounted } from 'vue'
import App from './App.vue'
import { setStorageUser } from './utils/storage.js'
const ready = ref(false), checking = ref(true), insecure = ref(false), userId = ref(''), message = ref('')
const authenticated = ref(false), userName = ref(''), personalAccess = ref(true)
const manualCheck = ref(false), feedback = ref(''), linkError = ref('')
provide('personalAccess', personalAccess)
let timer, pending, disposed = false
const pair = ref(null), starting = ref(false)
let pollTimer
async function pairRequest(action, body) {
  const response = await fetch('/api/v1/access/pair/' + action, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-JAgent-Console': '1' },
    body: JSON.stringify(body), cache: 'no-store'
  })
  const result = await response.json()
  if (!response.ok) throw Object.assign(new Error(result.message || 'Pairing failed. Try again.'), { status: response.status })
  return result
}
async function startPair() {
  starting.value = true
  feedback.value = ''
  try {
    const challenge = await pairRequest('start', {})
    if (disposed || authenticated.value) return
    pair.value = challenge
    async function poll() {
      if (disposed || authenticated.value || !pair.value) return
      try {
        if (Date.now() >= challenge.expiresAt) throw Object.assign(new Error('Pairing code expired. Request a new code.'), { status: 410 })
        const result = await pairRequest('poll', { claim: challenge.claim })
        if (disposed) return
        if (result.accepted) {
          pair.value = null
          feedback.value = ''
          if (pending) await pending
          await refresh()
          return
        }
      } catch (error) {
        feedback.value = error.message
        if (error.status >= 400 && error.status < 500) { pair.value = null; return }
      }
      if (!disposed) pollTimer = setTimeout(poll, 2000)
    }
    pollTimer = setTimeout(poll, 2000)
  } catch (error) { feedback.value = error.message }
  finally { starting.value = false }
}
function refresh() {
  if (!pending) pending = loadStatus().finally(() => { pending = null })
  return pending
}
async function loadStatus() {
  try {
    const response = await fetch('/api/v1/access/status', { cache: 'no-store' })
    if (response.status === 404) { personalAccess.value = false; ready.value = true; return } // Host applications may disable personal access.
    if (!response.ok) throw new Error('Harness is unavailable. Check your connection and try again.')
    const status = await response.json()
    if (disposed) return
    message.value = ''
    authenticated.value = Boolean(status.authenticated)
    ready.value = authenticated.value && Boolean(status.mainbotOnline)
    if (authenticated.value) {
      pair.value = null
      clearTimeout(pollTimer)
      setStorageUser(status.user.userId)
      userId.value = status.user.userId
      userName.value = status.user.name
      insecure.value = status.insecure && !['localhost', '127.0.0.1', '[::1]'].includes(location.hostname)
      linkError.value = ''
    }
  } catch (error) { ready.value = false; message.value = error.message }
  finally { checking.value = false }
}
async function checkConnection() {
  manualCheck.value = true
  feedback.value = ''
  try {
    await refresh()
    if (!disposed && !ready.value) {
      feedback.value = message.value ? 'Connection check failed. Try again when the Harness is reachable.'
        : authenticated.value ? 'Harness is reachable. Your mainbot Runner is still offline.'
          : 'Harness is reachable. This browser is not signed in.'
    }
  } finally { manualCheck.value = false }
}
async function connect() {
  const fragment = new URLSearchParams(location.hash.slice(1))
  const ticket = fragment.get('jagent-ticket')
  if (ticket) {
    history.replaceState(null, '', location.pathname + location.search)
    feedback.value = ''
    linkError.value = ''
    try {
      const response = await fetch('/api/v1/access/exchange', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-JAgent-Console': '1' }, body: JSON.stringify({ ticket }) })
      if (!response.ok) { const error = await response.json(); throw new Error(response.status === 401 ? 'This sign-in link has expired or was already used. Get a fresh link and open it here.' : error.message || 'Unable to use this sign-in link.') }
    } catch (error) { linkError.value = error.message }
    if (pending) await pending
  }
  await refresh()
}
onMounted(async () => {
  window.addEventListener('hashchange', connect)
  await connect()
  if (!disposed) timer = setInterval(refresh, 3000)
})
onUnmounted(() => { disposed = true; clearTimeout(pollTimer); clearInterval(timer); window.removeEventListener('hashchange', connect) })
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
.pair-command { display: inline-block; padding: 10px 14px; background: #eef2f7; border-radius: 6px; user-select: all; }
.pair-start { background: #2563eb; border-color: #2563eb; color: white; }
</style>
