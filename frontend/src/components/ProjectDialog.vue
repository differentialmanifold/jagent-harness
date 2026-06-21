<template>
  <el-dialog
    :model-value="open"
    title="Add new project"
    width="520px"
    align-center
    @close="$emit('close')"
  >
    <form class="dialog-form" @submit.prevent="$emit('submit')">
      <el-form-item label="Project name" :error="error">
        <el-input
          ref="nameInputEl"
          :model-value="projectName"
          placeholder="Project name"
          :disabled="submitting"
          @update:model-value="updateProjectName"
        />
      </el-form-item>
      <el-form-item v-if="workspaceEnabled" label="Workspace path">
        <el-input
          :model-value="workspacePath"
          placeholder="/Users/you/work/my-project"
          :disabled="submitting"
          @update:model-value="updateWorkspacePath"
        />
      </el-form-item>
    </form>

    <template #footer>
      <el-button :disabled="submitting" @click="$emit('close')">Cancel</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!canSubmit"
        @click="$emit('submit')"
      >
        Add project
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'

const props = defineProps({
  open: { type: Boolean, required: true },
  projectName: { type: String, required: true },
  workspacePath: { type: String, default: '' },
  workspaceEnabled: { type: Boolean, default: true },
  submitting: { type: Boolean, required: true },
  error: { type: String, default: '' }
})

const emit = defineEmits(['update:projectName', 'update:workspacePath', 'submit', 'close'])

const nameInputEl = ref(null)
const nameTouched = ref(false)

const canSubmit = computed(() => (
  props.projectName.trim()
    && (!props.workspaceEnabled || props.workspacePath.trim())
))

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    nameTouched.value = Boolean(props.projectName.trim())
    await nextTick()
    if (nameInputEl.value) nameInputEl.value.focus()
  }
)

function updateProjectName(value) {
  nameTouched.value = true
  emit('update:projectName', value)
}

function updateWorkspacePath(value) {
  emit('update:workspacePath', value)
  if (!nameTouched.value) {
    emit('update:projectName', nameFromPath(value))
  }
}

function nameFromPath(path) {
  const normalized = String(path || '').replace(/[\\/]+$/, '')
  const parts = normalized.split(/[\\/]/).filter(Boolean)
  return parts.length > 0 ? parts[parts.length - 1] : ''
}
</script>
