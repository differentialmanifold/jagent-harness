<template>
  <div class="workspace-picker">
    <div class="workspace-path">
      <el-input :model-value="modelValue" placeholder="Absolute path on the runner" :disabled="disabled" @update:model-value="$emit('update:modelValue', $event)" @keydown.enter.prevent="openBrowser" />
      <el-button :disabled="disabled || loading" @click="openBrowser">Browse</el-button>
    </div>
    <div v-if="open" class="workspace-browser">
      <div class="workspace-navigation">
        <el-button size="small" :disabled="loading || !listing?.parent" @click="browse(listing.parent)">Up</el-button>
        <el-button v-for="root in listing?.roots || []" :key="root" size="small" :disabled="loading" @click="browse(root)">{{ root }}</el-button>
        <el-button size="small" :disabled="loading" @click="browse('')">Home</el-button>
      </div>
      <p class="workspace-current">{{ loading ? 'Loading directories on the runner…' : listing?.path }}</p>
      <p v-if="error" role="alert" class="workspace-error">{{ error }}</p>
      <div v-if="listing && !loading && !error" class="workspace-directories">
        <button v-for="directory in listing.directories" :key="directory" type="button" @click="browse(directory)">{{ basename(directory) }}</button>
        <span v-if="!listing.directories.length">No subdirectories</span>
      </div>
      <p v-if="listing?.truncated">Showing the first 1,000 directories. Enter a path to browse another folder.</p>
      <div class="workspace-actions">
        <el-button size="small" @click="open = false">Close</el-button>
        <el-button size="small" type="primary" :disabled="loading || !!error || !listing" @click="select">Use this folder</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { request } from '../api/http'
const props = defineProps({ modelValue: { type: String, default: '' }, runnerId: { type: String, required: true }, disabled: Boolean })
const emit = defineEmits(['update:modelValue'])
const open = ref(false)
const listing = ref(null)
const loading = ref(false)
const error = ref('')
let version = 0
watch(() => props.runnerId, () => { version++; open.value = false; loading.value = false; listing.value = null; error.value = '' })
function basename(path) { return path.split(/[/\\]/).filter(Boolean).pop() || path }
function openBrowser() { if (!props.disabled && !loading.value) { open.value = true; browse(props.modelValue) } }
async function browse(path) {
  const current = ++version
  loading.value = true
  error.value = ''
  try {
    const value = await request(`/api/v1/runners/${encodeURIComponent(props.runnerId)}/workspaces?path=${encodeURIComponent(path || '')}`)
    if (version === current) listing.value = value
  } catch (reason) {
    if (version === current) error.value = reason.message
  } finally {
    if (version === current) loading.value = false
  }
}
function select() { emit('update:modelValue', listing.value.path); open.value = false }
</script>

<style scoped>
.workspace-picker { width: 100%; }
.workspace-path, .workspace-navigation, .workspace-actions { display: flex; gap: 8px; }
.workspace-browser { margin-top: 10px; padding: 12px; border: 1px solid var(--el-border-color); border-radius: 8px; }
.workspace-current { margin: 10px 0; overflow-wrap: anywhere; line-height: 1.4; }
.workspace-directories { display: flex; flex-direction: column; max-height: 180px; overflow-y: auto; }
.workspace-directories button { background: transparent; border: 0; padding: 7px 8px; color: var(--el-text-color-primary); text-align: left; cursor: pointer; overflow-wrap: anywhere; }
.workspace-directories button:hover { background: var(--el-fill-color-light); }
.workspace-actions { justify-content: flex-end; margin-top: 10px; }
.workspace-error { color: var(--el-color-danger); }
</style>
