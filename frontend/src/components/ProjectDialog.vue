<template>
  <div v-if="open" class="modal-backdrop" @click.self="$emit('close')">
    <form class="project-dialog" role="dialog" aria-modal="true" @submit.prevent="$emit('submit')">
      <header>
        <h2>Add new project</h2>
        <button class="icon-button subtle" type="button" title="Close" @click="$emit('close')">X</button>
      </header>

      <label for="project-path-input">Project path</label>
      <input
        id="project-path-input"
        ref="inputEl"
        :value="modelValue"
        placeholder="/Users/you/work/my-project"
        :disabled="submitting"
        @input="$emit('update:modelValue', $event.target.value)"
      />
      <small v-if="error">{{ error }}</small>

      <footer>
        <button class="secondary-button" type="button" :disabled="submitting" @click="$emit('close')">
          Cancel
        </button>
        <button class="primary-button" :disabled="submitting || !modelValue.trim()" type="submit">
          {{ submitting ? 'Checking...' : 'Add project' }}
        </button>
      </footer>
    </form>
  </div>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'

const props = defineProps({
  open: { type: Boolean, required: true },
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
    if (inputEl.value) inputEl.value.focus()
  }
)
</script>
