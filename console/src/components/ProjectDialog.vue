<template>
  <el-dialog :model-value="open" title="Add new project" width="520px" align-center @close="$emit('close')">
    <el-form class="dialog-form project-dialog-form" label-position="top" @submit.prevent="submit">
      <el-form-item label="Execution target">
        <el-select :model-value="runnerId" :empty-values="[null, undefined]" :disabled="submitting" style="width: 100%" @update:model-value="$emit('update:runnerId', $event)">
          <el-option label="Server tools only" value="" />
          <el-option v-for="runner in runners" :key="runner.runnerId" :value="runner.runnerId"
            :label="runner.name + (runner.online ? ' · Online' : ' · Offline')" :disabled="!runner.online" />
        </el-select>
        <p v-if="selectedRunner" class="runner-workspace">{{ selectedRunner.tools?.length || 0 }} local tools on this terminal.</p>
        <p v-else-if="!runners.length" class="runner-workspace">Start a runner to use tools on another machine.</p>
      </el-form-item>
      <el-form-item v-if="selectedRunner?.workspaces" label="Workspace on runner">
        <RunnerWorkspacePicker :model-value="workspace" :runner-id="runnerId" :disabled="submitting" @update:model-value="updateWorkspacePath" />
      </el-form-item>
      <el-form-item label="Project name" :error="error">
        <el-input ref="nameInputEl" :model-value="projectName" placeholder="Project name" :disabled="submitting" @update:model-value="updateProjectName" />
      </el-form-item>
    </el-form>
    <template #footer>
      <div class="project-dialog-footer">
        <el-button :disabled="submitting" @click="$emit('close')">Cancel</el-button>
        <el-button class="project-dialog-submit" type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">Add project</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ElSelect, ElOption } from 'element-plus'
import RunnerWorkspacePicker from './RunnerWorkspacePicker.vue'
const props = defineProps({
  runnerId: { type: String, default: '' },
  runners: { type: Array, default: () => [] },
  open: { type: Boolean, required: true },
  projectName: { type: String, required: true },
  submitting: { type: Boolean, required: true },
  error: { type: String, default: '' }
})
const emit = defineEmits(['update:runnerId', 'update:projectName', 'submit', 'close'])
const selectedRunner = computed(() => props.runners.find(runner => runner.runnerId === props.runnerId))
const workspace = ref('')
const autoProjectName = ref('')
const canSubmit = computed(() => props.projectName.trim() && (!selectedRunner.value?.workspaces || workspace.value.trim()))
function reset() { updateWorkspacePath('') }
watch(() => props.runnerId, reset)
function updateProjectName(value) {
  if (value !== autoProjectName.value) autoProjectName.value = ''
  emit('update:projectName', value)
}
function updateWorkspacePath(value) {
  workspace.value = value
  const currentName = props.projectName.trim()
  if (!currentName || currentName === autoProjectName.value) {
    const parts = String(value || '').split(/[\\/]/).filter(Boolean)
    const nextName = parts.length ? parts[parts.length - 1] : ''
    autoProjectName.value = nextName
    emit('update:projectName', nextName)
  }
}
function submit() { if (canSubmit.value && !props.submitting) emit('submit', { workspacePath: workspace.value }) }
const nameInputEl = ref(null)
watch(() => props.open, async open => {
  if (open) { reset(); await nextTick(); nameInputEl.value?.focus() }
})
</script>

<style scoped>
.runner-workspace { margin: 8px 0 0; line-height: 1.5; font-size: 12px; color: var(--el-text-color-secondary); overflow-wrap: anywhere; }
</style>
