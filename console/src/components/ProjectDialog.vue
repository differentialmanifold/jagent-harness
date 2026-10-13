<template>
  <el-dialog :model-value="open" title="Add new project" width="520px" align-center @close="$emit('close')">
    <el-form class="dialog-form project-dialog-form" label-position="top" @submit.prevent="$emit('submit')">
      <el-form-item label="Execution target">
        <el-select :model-value="runnerId" :empty-values="[null, undefined]" :disabled="submitting" style="width: 100%" @update:model-value="$emit('update:runnerId', $event)">
          <el-option label="Server tools only" value="" />
          <el-option v-for="runner in runners" :key="runner.runnerId" :value="runner.runnerId"
            :label="runner.name + (runner.online ? ' · Online' : ' · Offline')" :disabled="!runner.online" />
        </el-select>
        <p v-if="selectedRunner" class="runner-workspace"><template v-if="selectedRunner.workspace">{{ selectedRunner.workspace }} · </template>{{ selectedRunner.tools?.length || 0 }} local tools. Tools and permissions are configured on the runner.</p>
        <p v-else-if="!runners.length" class="runner-workspace">Start a runner to use tools on another machine.</p>
      </el-form-item>
      <el-form-item label="Project name" :error="error">
        <el-input ref="nameInputEl" :model-value="projectName" placeholder="Project name" :disabled="submitting" @update:model-value="$emit('update:projectName', $event)" />
      </el-form-item>
    </el-form>
    <template #footer>
      <div class="project-dialog-footer">
        <el-button :disabled="submitting" @click="$emit('close')">Cancel</el-button>
        <el-button class="project-dialog-submit" type="primary" :loading="submitting" :disabled="!projectName.trim()" @click="$emit('submit')">Add project</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ElSelect, ElOption } from 'element-plus'
const props = defineProps({
  runnerId: { type: String, default: '' },
  runners: { type: Array, default: () => [] },
  open: { type: Boolean, required: true },
  projectName: { type: String, required: true },
  submitting: { type: Boolean, required: true },
  error: { type: String, default: '' }
})
defineEmits(['update:runnerId', 'update:projectName', 'submit', 'close'])
const selectedRunner = computed(() => props.runners.find(runner => runner.runnerId === props.runnerId))
const nameInputEl = ref(null)
watch(() => props.open, async open => {
  if (open) { await nextTick(); nameInputEl.value?.focus() }
})
</script>

<style scoped>
.runner-workspace { margin: 8px 0 0; line-height: 1.5; font-size: 12px; color: var(--el-text-color-secondary); overflow-wrap: anywhere; }
</style>
