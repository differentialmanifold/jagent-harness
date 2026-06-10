<template>
  <el-dialog
    :model-value="open"
    :title="title"
    width="480px"
    align-center
    @close="$emit('close')"
  >
    <form class="dialog-form" @submit.prevent="$emit('submit')">
      <el-form-item :label="label" :error="error">
        <el-input
          ref="inputEl"
          :model-value="modelValue"
          :disabled="submitting"
          @update:model-value="$emit('update:modelValue', $event)"
        />
      </el-form-item>
    </form>

    <template #footer>
      <el-button :disabled="submitting" @click="$emit('close')">Cancel</el-button>
      <el-button
        type="primary"
        :loading="submitting"
        :disabled="!modelValue.trim()"
        @click="$emit('submit')"
      >
        Save
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'

const props = defineProps({
  open: { type: Boolean, required: true },
  title: { type: String, required: true },
  label: { type: String, required: true },
  modelValue: { type: String, required: true },
  submitting: { type: Boolean, required: true },
  error: { type: String, default: '' }
})

defineEmits(['update:modelValue', 'submit', 'close'])

const inputEl = ref(null)

watch(
  () => props.open,
  async (open) => {
    if (!open) return
    await nextTick()
    if (inputEl.value) {
      inputEl.value.focus()
      inputEl.value.select()
    }
  }
)
</script>
