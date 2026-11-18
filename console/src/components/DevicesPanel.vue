<template>
  <section class="devices-panel">
    <div class="devices-actions">
      <p>Open this workspace in another browser on this computer, or on another device.</p>
      <el-button type="primary" :loading="creating" @click="createLink">Add device</el-button>
    </div>
    <el-alert v-if="error" type="error" :closable="false" :title="error" />
    <el-empty v-if="!loading && !devices.length" description="No authorized browsers" />
    <ul class="device-list" v-loading="loading">
      <li v-for="device in devices" :key="device.id">
        <div><strong>{{ device.current ? 'This browser' : `Browser ${device.id.slice(0, 8)}` }}</strong>
          <span>Automatically expires on <time :datetime="new Date(device.expiresAt).toISOString()">{{ expiryFormat.format(device.expiresAt) }}</time></span>
        </div>
        <el-button :loading="revoking === device.id" @click="revoke(device)">{{ device.current ? 'Sign out' : 'Revoke access' }}</el-button>
      </li>
    </ul>
    <p class="devices-note">Each browser is authorized for 30 days from sign-in. Sign out or revoke access to end a session early.</p>

    <el-dialog v-model="sharing" title="Add a device" width="420px" :close-on-click-modal="false" @closed="clearLink">
      <div class="device-share">
        <template v-if="secondsLeft > 0 && link">
          <img :src="qr" alt="Scan to authorize another browser" width="224" height="224" />
          <p>For another browser, copy the link and paste it into its address bar. For a phone, scan the code.</p>
          <el-input :model-value="link" readonly aria-label="Single-use access link" />
          <el-button @click="copyLink">{{ copied ? 'Copied' : 'Copy link' }}</el-button>
          <small role="status">Single use · expires in {{ secondsLeft }}s. Only share with a device you trust.</small>
          <el-alert v-if="loopback" type="info" :closable="false" title="This link works in another browser on this computer. For a phone or another computer, create the link from a Console network address that device can reach." />
        </template>
        <template v-else>
          <p>This link has expired.</p>
          <el-button type="primary" :loading="creating" @click="createLink">Create new link</el-button>
        </template>
      </div>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import QRCode from 'qrcode'
import { request } from '../api/http.js'
import { writeClipboard } from '../utils/clipboard.js'

const expiryFormat = new Intl.DateTimeFormat(undefined, {
  year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', second: '2-digit', timeZoneName: 'short'
})
const devices = ref([]), loading = ref(false), creating = ref(false), revoking = ref(''), error = ref('')
const sharing = ref(false), link = ref(''), qr = ref(''), expiresAt = ref(0), now = ref(Date.now()), copied = ref(false)
const secondsLeft = computed(() => Math.max(0, Math.ceil((expiresAt.value - now.value) / 1000)))
const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname)
let timer
async function load() {
  loading.value = true
  try { devices.value = await request('/api/v1/access/devices') } catch (e) { error.value = e.message }
  finally { loading.value = false }
}
async function createLink() {
  creating.value = true; error.value = ''; copied.value = false
  try {
    const ticket = await request('/api/v1/access/devices/link', { method: 'POST', body: '{}' })
    const url = new URL('/', location.origin); url.hash = new URLSearchParams({ 'jagent-ticket': ticket.ticket }).toString()
    link.value = url.toString(); expiresAt.value = ticket.expiresAt; now.value = Date.now()
    qr.value = await QRCode.toDataURL(link.value, { width: 224, margin: 1 })
    sharing.value = true
  } catch (e) { error.value = e.message } finally { creating.value = false }
}
async function copyLink() {
  try { await writeClipboard(link.value); copied.value = true } catch { error.value = 'Could not copy the link. Select and copy it manually.' }
}
function clearLink() { link.value = ''; qr.value = ''; expiresAt.value = 0; load() }
async function revoke(device) {
  try { await ElMessageBox.confirm(device.current ? 'Sign out of this browser?' : 'Revoke access for this browser? Pair again or use a fresh link to sign in.', 'Browser access', { confirmButtonText: device.current ? 'Sign out' : 'Revoke access', type: 'warning' }) } catch { return }
  revoking.value = device.id; error.value = ''
  try {
    await request(`/api/v1/access/devices/${device.id}`, { method: 'DELETE' })
    if (device.current) location.reload()
    else await load()
  } catch (e) { error.value = e.message } finally { revoking.value = '' }
}
watch(sharing, open => {
  clearInterval(timer)
  if (open) timer = setInterval(() => { now.value = Date.now() }, 1000)
})
onMounted(load)
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.devices-panel { padding: 24px 28px; max-width: 900px; }
.devices-actions { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 20px; }
.devices-actions p, .devices-note { color: var(--text-secondary, #64748b); line-height: 1.6; }
.device-list { padding: 0; margin: 0; list-style: none; }
.device-list li { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 18px 0; border-bottom: 1px solid #e5e7eb; }
.device-list strong, .device-list span { display: block; }
.device-list span { color: #64748b; margin-top: 6px; font-size: 13px; }
.devices-note { font-size: 13px; }
.device-share { display: flex; flex-direction: column; align-items: center; gap: 16px; text-align: center; }
.device-share p { margin: 0; line-height: 1.6; }
.device-share small { color: #64748b; }
@media (max-width: 640px) { .devices-panel { padding: 16px; } .devices-actions { align-items: flex-start; flex-direction: column; gap: 8px; } }
</style>
