<template>
  <textarea
    ref="inputEl"
    v-model="text"
    class="composer-input"
    rows="3"
    aria-label="Message"
    @input="resize"
  />
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({ modelValue: { type: String, default: '' } })
const emit = defineEmits(['update:modelValue'])
const inputEl = ref(null)
const text = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})
let observer
let observedWidth = 0

function resize() {
  const el = inputEl.value
  if (!el) return
  const scrollTop = el.scrollTop
  // Change only the dimensions; assigning value here would reset native undo history.
  el.style.height = 'auto'
  const style = window.getComputedStyle(el)
  const border = parseFloat(style.borderTopWidth) + parseFloat(style.borderBottomWidth)
  el.style.height = `${el.scrollHeight + border}px`
  el.scrollTop = scrollTop
}

watch(() => props.modelValue, () => nextTick(resize))
onMounted(() => {
  resize()
  observer = new ResizeObserver(([entry]) => {
    if (entry.contentRect.width !== observedWidth) {
      observedWidth = entry.contentRect.width
      resize()
    }
  })
  observer.observe(inputEl.value)
  window.addEventListener('resize', resize)
})
onBeforeUnmount(() => {
  observer?.disconnect()
  window.removeEventListener('resize', resize)
})
</script>
