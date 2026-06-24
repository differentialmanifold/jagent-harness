<template>
  <el-dialog
    :model-value="open"
    title="Add new project"
    width="520px"
    align-center
    @close="$emit('close')"
  >
    <el-form class="dialog-form project-dialog-form" label-position="top" @submit.prevent="$emit('submit')">
      <el-form-item v-if="workspaceEnabled" label="Workspace path">
        <el-input
          ref="workspaceInputEl"
          :model-value="workspacePath"
          placeholder="/Users/you/work/my-project"
          :disabled="submitting"
          @update:model-value="updateWorkspacePath"
        />
      </el-form-item>
      <el-form-item label="Project name" :error="error">
        <el-input
          ref="nameInputEl"
          :model-value="projectName"
          placeholder="Project name"
          :disabled="submitting"
          @update:model-value="updateProjectName"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <div class="project-dialog-footer">
        <el-button :disabled="submitting" @click="$emit('close')">Cancel</el-button>
        <el-button
          class="project-dialog-submit"
          type="primary"
          :loading="submitting"
          :disabled="!canSubmit"
          @click="$emit('submit')"
        >
          Add project
        </el-button>
      </div>
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
const workspaceInputEl = ref(null)
const autoProjectName = ref('')

const canSubmit = computed(() => (
  props.projectName.trim()
    && (!props.workspaceEnabled || props.workspacePath.trim())
))

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    autoProjectName.value = isGeneratedProjectName(props.projectName, props.workspacePath)
      ? props.projectName
      : ''
    await nextTick()
    const input = props.workspaceEnabled ? workspaceInputEl.value : nameInputEl.value
    if (input) input.focus()
  }
)

function updateProjectName(value) {
  if (value !== autoProjectName.value) {
    autoProjectName.value = ''
  }
  emit('update:projectName', value)
}

function updateWorkspacePath(value) {
  emit('update:workspacePath', value)
  if (shouldAutoUpdateProjectName()) {
    const nextName = nameFromPath(value)
    autoProjectName.value = nextName
    emit('update:projectName', nextName)
  }
}

function shouldAutoUpdateProjectName() {
  const currentName = props.projectName.trim()
  return !currentName || currentName === autoProjectName.value
}

function isGeneratedProjectName(projectName, workspacePath) {
  const name = String(projectName || '').trim()
  return name && name === nameFromPath(workspacePath)
}

function nameFromPath(path) {
  const normalized = String(path || '').replace(/[\\/]+$/, '')
  const parts = normalized.split(/[\\/]/).filter(Boolean)
  return parts.length > 0 ? parts[parts.length - 1] : ''
}
</script>
